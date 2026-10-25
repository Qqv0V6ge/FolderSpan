#!/usr/bin/env python3
"""Verify two Android WebRTC Session endpoints using a localhost signaling bridge."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from queue import Queue, Empty, Full
import subprocess
from threading import Thread
import time

import verify_webrtc_interop as interop

PACKAGE = "com.folderspan.core.test"
CONFIG = "files/webrtc-interop-config.json"


class Bridge(ThreadingHTTPServer):
    def __init__(self, address=("127.0.0.1", 0)):
        self.signals = {role: Queue(maxsize=128) for role in ("server", "client")}
        self.values = {"bootstrap": b"", "evidence": b"", "observations": b""}
        self.finished = set()
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Signaling and bootstrap bodies contain authorization material.

    def do_GET(self):
        self.respond()

    def do_POST(self):
        self.respond()

    def respond(self):
        try:
            _, role, route = self.path.split("/")
            if role not in self.server.signals:
                raise ValueError("Unknown endpoint")
            body = b""
            if self.command == "POST":
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 <= size <= 1_048_576:
                    raise ValueError("Body too large")
                body = self.rfile.read(size)
            response = b"ok"
            if route in self.server.values:
                if self.command == "POST":
                    self.server.values[route] = body
                else:
                    response = self.server.values[route]
            elif route == "incoming" and self.command == "POST":
                self.server.signals["client" if role == "server" else "server"].put_nowait(body)
            elif route == "outgoing" and self.command == "GET":
                try:
                    response = self.server.signals[role].get_nowait()
                except Empty:
                    response = b""
            elif route == "finished" and self.command == "POST":
                self.server.finished.add(role)
            elif route != "joined" or self.command != "POST":
                raise ValueError("Unknown route")
            self.send_response(200 if response else 204)
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)
        except (ValueError, OverflowError, Full):
            self.send_error(400)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, help="Android serial for the authenticated server and initial offerer")
    parser.add_argument("--client", required=True, help="Android serial for the authenticated client")
    parser.add_argument("--expected-path", choices=("Direct", "Relay"), required=True)
    parser.add_argument("--expected-family", choices=("IPv4", "IPv6"), default="")
    args = parser.parse_args()
    if args.server == args.client:
        parser.error("Two different Android devices are required")
    output = interop.ROOT / "build/webrtc-ice-evidence/android-pair"
    output.mkdir(parents=True, exist_ok=True)
    (output / "result.json").unlink(missing_ok=True)
    interop.OUTPUT = output
    config = dict(
        stunUrl=os.environ.get("FOLDERSPAN_WEBRTC_TEST_STUN_URL", ""),
        turnUrl=os.environ.get("FOLDERSPAN_WEBRTC_TEST_TURN_URL", ""),
        turnUsername=os.environ.get("FOLDERSPAN_WEBRTC_TEST_TURN_USERNAME", ""),
        turnPassword=os.environ.get("FOLDERSPAN_WEBRTC_TEST_TURN_PASSWORD", ""),
        expectedPath=args.expected_path, expectedFamily=args.expected_family,
        expectedProtocol="Udp", networkScenario="android-pair",
    )
    builds, reverses, configs, devices = [], [], [], []
    bridge = Bridge()
    thread = Thread(target=bridge.serve_forever, daemon=True)
    thread.start()
    port = bridge.server_address[1]
    try:
        build = interop.Build("compile", [":core:assembleAndroidDeviceTest"], dict(os.environ))
        builds.append(build)
        build.wait(timeout=600)
        build.close()
        builds.remove(build)
        for role, serial in (("server", args.server), ("client", args.client)):
            adb = ["adb", "-s", serial]
            subprocess.run(adb + ["get-state"], check=True, timeout=10, capture_output=True)
            subprocess.run(adb + ["install", "-r", "-t", str(interop.ROOT / "core/build/outputs/apk/androidTest/core-androidTest.apk")], check=True, timeout=60, capture_output=True)
            if subprocess.run(adb + ["shell", "run-as", PACKAGE, "test", "-e", CONFIG], timeout=10).returncode != 1:
                raise RuntimeError(f"Existing test configuration or inaccessible package on {serial}")
            subprocess.run(adb + ["shell", "run-as", PACKAGE, "mkdir", "-p", "files"], check=True, timeout=10)
            configs.append(adb)
            subprocess.run(adb + ["shell", "run-as", PACKAGE, "sh", "-c", "'umask 077; cat > " + CONFIG + "'"], input=json.dumps(config).encode(), check=True, timeout=10)
            subprocess.run(adb + ["reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}"], check=True, timeout=10)
            reverses.append(adb)
            interop.GRADLE = adb
            test_class = "WebRtcInteropAndroidServerTest" if role == "server" else "WebRtcInteropBrowserTest"
            devices.append(adb)
            test = interop.Build(role, [
                "shell", "am", "instrument", "-w", "-r", "-e", "class", "com.folderspan.service.webrtc." + test_class,
                "-e", "webrtcInteropInitiator", str(role == "server").lower(),
                "-e", "webrtcInteropUrl", f"http://127.0.0.1:{port}/{role}",
                PACKAGE + "/androidx.test.runner.AndroidJUnitRunner",
            ], dict(os.environ))
            builds.append(test)
            deadline = time.monotonic() + 15
            while True:
                pid = subprocess.run(adb + ["shell", "pidof", PACKAGE], capture_output=True, text=True, timeout=3).stdout.strip()
                if pid:
                    break
                if test.process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f"Test package did not start on {serial}")
                time.sleep(.1)
            builds.append(interop.Build(role + "-logcat", ["logcat", "--pid=" + pid, "-v", "brief", "System.out:I", "TestRunner:I", "*:S"], dict(os.environ)))
        for role in ("client", "server"):
            next(build for build in builds if build.name == role).wait(timeout=150, on_tick=lambda: [build.remaining() for build in builds])
            assert "OK (1 test)" in (output / (role + ".log")).read_text(), f"{role} instrumentation failed"
        assert bridge.finished == {"server", "client"}
        observations = json.loads(bridge.values["observations"])
        assert {item["observation"].get("source", "Local") for item in observations} == {"Local", "Remote"}
        assert all(item["rpcVerified"] and item["iceRestartApplied"] for item in observations)
        client_log = (output / "client-logcat.log").read_text()
        paths = [json.loads(line.split("WEBRTC_ICE_CLIENT_PATH ", 1)[1]) for line in client_log.splitlines() if "WEBRTC_ICE_CLIENT_PATH " in line]
        assert len(paths) == 1 and paths[0]["kind"] == args.expected_path
        result = dict(server=args.server, client=args.client, testsPerEndpoint=1,
                      scope="Android emulator/device networks; no public-network claim",
                      observations=observations, clientLocalPathAfterRestart=paths[0])
    finally:
        errors = []
        cleanup = [(adb, ["shell", "am", "force-stop", PACKAGE]) for adb in devices]
        cleanup += [(adb, ["reverse", "--remove", f"tcp:{port}"]) for adb in reverses]
        cleanup += [(adb, ["shell", "run-as", PACKAGE, "rm", "-f", CONFIG]) for adb in configs]
        for adb, command in cleanup:
            try:
                subprocess.run(adb + command, timeout=10, check=True)
            except Exception as error:
                errors.append(str(error))
        for build in reversed(builds):
            try:
                build.close()
                assert not build.remaining()
            except Exception as error:
                errors.append(str(error))
        bridge.shutdown()
        thread.join(timeout=5)
        bridge.server_close()
        if errors or thread.is_alive():
            raise RuntimeError("; ".join(errors) or "Signaling bridge did not stop")
    result["processesStopped"] = True
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
