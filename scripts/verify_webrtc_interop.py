#!/usr/bin/env python3
"""Verify native and browser Session endpoints; retain logs under build/."""

import json
import argparse
import ipaddress
import hashlib
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build" / "webrtc-interop"
GRADLE = [
    str(ROOT / "gradlew"), "--no-daemon", "--console=plain", "--max-workers=1",
    "-Dorg.gradle.jvmargs=-Xmx4g",
    "-Pkotlin.compiler.execution.strategy=in-process", "-PwebrtcInterop",
    "-PincludeProMain=false",
]


def process_table():
    rows = subprocess.check_output(["ps", "-axo", "pid=,ppid=,lstart=,stat="], text=True)
    result = {}
    for row in rows.splitlines():
        fields = row.split()
        if len(fields) >= 8:
            result[int(fields[0])] = (int(fields[1]), " ".join(fields[2:7]), fields[7])
    return result


class Build:
    def __init__(self, name, tasks, environment):
        self.name = name
        self.log = (OUTPUT / (name + ".log")).open("w")
        self.process = subprocess.Popen(
            GRADLE + tasks, cwd=ROOT, env=environment, stdout=self.log,
            stderr=subprocess.STDOUT, start_new_session=True,
        )
        self.seen = {}
        print(f"{name}: started (pid {self.process.pid})", flush=True)

    def remaining(self):
        table = process_table()
        parents = {self.process.pid} | set(self.seen)
        changed = True
        while changed:
            changed = False
            for pid, (ppid, start, _) in table.items():
                if (pid == self.process.pid or ppid in parents) and pid not in self.seen:
                    self.seen[pid] = start
                    parents.add(pid)
                    changed = True
        return {pid: row for pid, row in table.items() if self.seen.get(pid) == row[1] and "Z" not in row[2]}

    def wait(self, timeout=300, on_tick=lambda: None):
        deadline = time.monotonic() + timeout
        while self.process.poll() is None:
            self.remaining()
            on_tick()
            if time.monotonic() >= deadline:
                raise TimeoutError(f"{self.name} exceeded {timeout} seconds")
            time.sleep(0.5)
        if self.process.returncode:
            raise RuntimeError(f"{self.name} failed; see {OUTPUT / (self.name + '.log')}")

    def close(self):
        for sig in (None, signal.SIGINT, signal.SIGTERM):
            active = self.remaining()
            if not active:
                break
            if sig:
                for pid in active:
                    try:
                        os.kill(pid, sig)
                    except ProcessLookupError:
                        pass
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline and self.remaining():
                self.process.poll()
                time.sleep(0.2)
        active = self.remaining()
        self.log.close()
        if active:
            raise RuntimeError(f"{self.name} left processes running: {list(active)}")


def check_report(task, test_class):
    files = list((ROOT / "core/build/test-results" / task).glob("*.xml"))
    suites = [ET.parse(path).getroot() for path in files if test_class in path.name]
    totals = {key: sum(int(suite.get(key, 0)) for suite in suites) for key in ("tests", "failures", "errors", "skipped")}
    if totals != {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}:
        raise RuntimeError(f"Invalid {task} result: {totals}")
    return totals


def evidence_lines(stdout):
    prefix = "WEBRTC_ICE_EVIDENCE "
    return [json.loads(line[len(prefix):]) for line in stdout.splitlines() if line.startswith(prefix)]


def native_evidence():
    observations = []
    for path in (ROOT / "core/build/test-results/jvmTest").glob("*WebRtcInteropJvmTest*.xml"):
        for output in ET.parse(path).getroot().iter("system-out"):
            observations.extend(evidence_lines(output.text or ""))
    if {item["observation"].get("source", "Local") for item in observations} != {"Local", "Remote"}:
        raise RuntimeError("Missing native/local or client/remote ICE evidence")
    return observations


def options(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--role", choices=("local", "native", "browser", "jvm-client"), default="local")
    parser.add_argument("--bridge-url", help="Reachable HTTP test signaling endpoint; required for separate networks")
    parser.add_argument("--bind", default="127.0.0.1", help="Native test bridge bind address")
    parser.add_argument("--port", type=int)
    parser.add_argument("--browser-target", choices=("js", "wasmJs"), default="js")
    parser.add_argument("--expected-path", choices=("Direct", "Relay"))
    parser.add_argument("--expected-family", choices=("IPv4", "IPv6"))
    parser.add_argument("--expected-protocol", choices=("Udp", "Tcp"))
    parser.add_argument("--network-scenario", default="local", help="Evidence label; external scenarios require separate endpoint runs")
    parser.add_argument("--keep-existing-lockfiles", action="store_true", help="Skip Yarn lock storage tasks for this verification run and assert stored locks stay unchanged")
    args = parser.parse_args(arguments)
    if args.port is not None and not 1 <= args.port <= 65535:
        parser.error("port must be in 1..65535")
    if args.role != "local" and not args.bridge_url:
        parser.error("separate endpoint runs require --bridge-url")
    if args.bridge_url:
        url = urllib.parse.urlsplit(args.bridge_url)
        if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.query or url.fragment:
            parser.error("bridge URL must be HTTP(S), without credentials, query or fragment")
    if args.network_scenario != "local":
        if args.role == "local" or not args.expected_path:
            parser.error("network evidence requires separate endpoint runs and --expected-path")
        host = urllib.parse.urlsplit(args.bridge_url).hostname
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = host.lower() == "localhost"
        if loopback:
            parser.error("localhost is not a remote network signaling endpoint")
    return args


def main():
    args = options()
    locks = {path: hashlib.sha256(path.read_bytes()).hexdigest() for path in (ROOT / "kotlin-js-store").rglob("yarn.lock")}
    if args.keep_existing_lockfiles:
        GRADLE.extend(["-x", ":kotlinStoreYarnLock", "-x", ":kotlinWasmStoreYarnLock"])
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "result.json").unlink(missing_ok=True)
    builds = []
    environment = dict(os.environ)
    explicit_port = args.port
    if args.role == "native" and explicit_port is None:
        parsed_url = urllib.parse.urlsplit(args.bridge_url)
        explicit_port = parsed_url.port or (443 if parsed_url.scheme == "https" else 80)
    for port in ([explicit_port] if explicit_port else range(18390, 18421)):
        with socket.socket(socket.AF_INET6 if ":" in args.bind else socket.AF_INET) as probe:
            try:
                probe.bind((args.bind, port))
            except OSError:
                continue
            break
    else:
        raise RuntimeError("No free localhost interop port")
    environment["FOLDERSPAN_WEBRTC_INTEROP_PORT"] = str(port)
    environment["FOLDERSPAN_WEBRTC_INTEROP_BIND"] = args.bind
    environment["FOLDERSPAN_WEBRTC_INTEROP_URL"] = args.bridge_url or f"http://127.0.0.1:{port}"
    configuration = {
        "stunUrl": environment.get("FOLDERSPAN_WEBRTC_TEST_STUN_URL", ""),
        "turnUrl": environment.get("FOLDERSPAN_WEBRTC_TEST_TURN_URL", ""),
        "turnUsername": environment.get("FOLDERSPAN_WEBRTC_TEST_TURN_USERNAME", ""),
        "turnPassword": environment.get("FOLDERSPAN_WEBRTC_TEST_TURN_PASSWORD", ""),
        "expectedPath": args.expected_path or "",
        "expectedFamily": args.expected_family or "",
        "expectedProtocol": args.expected_protocol or "",
        "networkScenario": args.network_scenario,
    }
    environment["FOLDERSPAN_WEBRTC_INTEROP_CONFIG"] = json.dumps(configuration)
    try:
        suffix = "Js" if args.browser_target == "js" else "WasmJs"
        compile_tasks = []
        if args.role != "browser":
            compile_tasks.append(":core:compileTestKotlinJvm")
        if args.role in ("local", "browser"):
            compile_tasks.append(f":core:compileTestDevelopmentExecutableKotlin{suffix}")
        compile_build = Build("compile", compile_tasks, environment)
        builds.append(compile_build)
        compile_build.wait(timeout=600)
        compile_build.close()
        builds.remove(compile_build)
        native = None
        if args.role in ("local", "native"):
            native = Build("jvm", [":core:jvmTest", "--tests", "com.folderspan.service.webrtc.WebRtcInteropJvmTest"], environment)
            builds.append(native)
        if args.role == "native":
            native.wait(timeout=600)
            results = {"jvm": check_report("jvmTest", "WebRtcInteropJvmTest")}
        else:
            results = run_browser(args, environment, builds, native)
        if native:
            results["observations"] = native_evidence()
            results["nativeEndpointRole"] = "client" if environment.get("FOLDERSPAN_WEBRTC_INTEROP_NATIVE_CLIENT") == "1" else "server"
        results["networkScenario"] = args.network_scenario
        results["endpointRole"] = args.role
        if args.role in ("local", "browser"):
            results["browserTarget"] = args.browser_target
        results["scope"] = "local smoke only" if args.role == "local" else "single endpoint; pair with the other endpoint's evidence"
        results["lockStorageSkipped"] = args.keep_existing_lockfiles
    finally:
        errors = []
        for build in reversed(builds):
            try:
                build.close()
            except Exception as error:
                errors.append(str(error))
        if errors:
            raise RuntimeError("; ".join(errors))
        if args.keep_existing_lockfiles and any(not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != before for path, before in locks.items()):
            raise RuntimeError("Stored Yarn locks changed during verification")
    (OUTPUT / "result.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results), flush=True)


def run_browser(args, environment, builds, native):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 120
    while True:
        if native:
            native.remaining()
        if (native and native.process.poll() is not None) or time.monotonic() >= deadline:
            raise RuntimeError("JVM signaling bridge did not start")
        try:
            with opener.open(environment["FOLDERSPAN_WEBRTC_INTEROP_URL"].rstrip("/") + "/ready", timeout=1) as response:
                if response.read() == b"ready":
                    break
        except (urllib.error.URLError, TimeoutError):
            time.sleep(0.25)
    task = "jvmTest" if args.role == "jvm-client" else args.browser_target + "BrowserTest"
    endpoint = "jvmClient" if args.role == "jvm-client" else "chrome"
    browser = Build(endpoint, [":core:" + task, "--tests", "com.folderspan.service.webrtc.WebRtcInteropBrowserTest.*"], environment)
    builds.append(browser)
    browser.wait(timeout=600, on_tick=native.remaining if native else lambda: None)
    results = {endpoint: check_report(task, "WebRtcInteropBrowserTest")}
    if native:
        native.wait()
        results["jvm"] = check_report("jvmTest", "WebRtcInteropJvmTest")
    return results

if __name__ == "__main__":
    main()
