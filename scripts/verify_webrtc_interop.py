#!/usr/bin/env python3
"""Run the JVM and Chrome Session endpoints together; retain logs under build/."""

import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build" / "webrtc-interop"
GRADLE = [
    str(ROOT / "gradlew"), "--no-daemon", "--console=plain", "--max-workers=1",
    "-Dorg.gradle.jvmargs=-Xmx4g",
    "-Pkotlin.compiler.execution.strategy=in-process", "-PwebrtcInterop",
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


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    builds = []
    environment = dict(os.environ)
    for port in range(18390, 18421):
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                continue
            break
    else:
        raise RuntimeError("No free localhost interop port")
    environment["FOLDERSPAN_WEBRTC_INTEROP_PORT"] = str(port)
    try:
        compile_build = Build("compile", [":core:compileTestKotlinJvm", ":core:compileTestDevelopmentExecutableKotlinJs"], environment)
        builds.append(compile_build)
        compile_build.wait(timeout=600)
        compile_build.close()
        builds.remove(compile_build)
        native = Build("jvm", [":core:jvmTest", "--tests", "com.folderspan.service.webrtc.WebRtcInteropJvmTest"], environment)
        builds.append(native)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 120
        while True:
            native.remaining()
            if native.process.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError("JVM signaling bridge did not start")
            try:
                with opener.open(f"http://127.0.0.1:{port}/ready", timeout=1) as response:
                    if response.read() == b"ready":
                        break
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.25)
        browser = Build("chrome", [":core:jsBrowserTest", "--tests", "com.folderspan.service.webrtc.WebRtcInteropBrowserTest.*"], environment)
        builds.append(browser)
        browser.wait(on_tick=native.remaining)
        native.wait()
        results = {
            "jvm": check_report("jvmTest", "WebRtcInteropJvmTest"),
            "chrome": check_report("jsBrowserTest", "WebRtcInteropBrowserTest"),
        }
        (OUTPUT / "result.json").write_text(json.dumps(results, indent=2) + "\n")
        print(json.dumps(results), flush=True)
    finally:
        errors = []
        for build in reversed(builds):
            try:
                build.close()
            except Exception as error:
                errors.append(str(error))
        if errors:
            raise RuntimeError("; ".join(errors))


if __name__ == "__main__":
    main()
