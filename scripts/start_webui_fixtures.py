#!/usr/bin/env python3
"""Disposable WebSocket v4 Runtimes and static Web site; stop with Ctrl+C.

Arguments: runtime-classpath.txt web-distribution-dir output-dir
Writes fixtures.json (public paths/ports) and private per-Runtime secret.txt.
Uses the real Headless main, without shared UI DI or business fixtures.
"""
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def free_device_port():
    while True:
        with socket.socket() as session, socket.socket() as approval:
            session.bind(("127.0.0.1", 0))
            port = session.getsockname()[1]
            if port > 65533:
                continue
            try:
                approval.bind(("127.0.0.1", port + 2))
                return port
            except OSError:
                continue


def main():
    classpath_file, distribution, output = map(lambda item: Path(item).resolve(), sys.argv[1:4])
    output.mkdir(parents=True, exist_ok=False)
    distribution = distribution.resolve(strict=True)
    # Keep the running JVM independent from later Gradle output updates.
    classpath = []
    repository = Path(__file__).resolve().parent.parent
    for index, item in enumerate(classpath_file.read_text().strip().split(os.pathsep)):
        source = Path(item)
        if source.is_relative_to(repository) and "build" in source.parts and source.exists():
            copy = output / "classpath" / str(index) / source.name
            copy.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, copy)
            else:
                shutil.copy2(source, copy)
            source = copy
        classpath.append(str(source))
    children = []
    runtimes = []
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")

    def start(args, directory, log, env=None):
        log.touch(mode=0o600)
        with log.open("w") as stream:
            child = subprocess.Popen(args, cwd=directory, stdout=stream, stderr=subprocess.STDOUT, env=env)
        children.append(child)
        return child

    def interrupted(*_):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    try:
        site_port = free_port()
        site_url = f"http://127.0.0.1:{site_port}"
        site = start([sys.executable, "-m", "http.server", str(site_port), "--bind", "127.0.0.1", "--directory", str(distribution)],
                     output, output / "site.log")
        deadline = time.monotonic() + 10
        while True:
            if site.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError("Static fixture failed; inspect site.log")
            try:
                with urllib.request.urlopen(site_url, timeout=1):
                    break
            except OSError:
                time.sleep(0.1)
        runtime_env = {**os.environ, "FOLDERSPAN_WEB_UI_ALLOWED_ORIGINS": site_url}
        for label in ("a", "b"):
            directory = output / label
            for name in ("home", "prefs/.java/.userPrefs", "system-prefs", "tmp"):
                (directory / name).mkdir(parents=True)
            port = free_port()
            (directory / "prefs/.java/.userPrefs/prefs.xml").write_text(
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">\n'
                f'<map MAP_XML_VERSION="1.0"><entry key="settings.webUi.port" value="{port}"/></map>\n'
            )
            (directory / "home/alpha.txt").write_text(label + ": alpha")
            args = ["-Xmx768m", "-Djava.awt.headless=true", f"-Duser.home={directory}/home",
                    f"-Djava.util.prefs.userRoot={directory}/prefs", f"-Djava.util.prefs.systemRoot={directory}/system-prefs",
                    f"-Djava.io.tmpdir={directory}/tmp", f"-Dfolderspan.webUi.root={distribution}",
                    "-cp", os.pathsep.join(classpath), "com.folderspan.runtime.MainKt"]
            argfile = directory / "java.args"
            argfile.write_text("\n".join('"' + item.replace('\\', '\\\\').replace('"', '\\"') + '"' for item in args))
            child = start([java, "@" + str(argfile)], directory, directory / "runtime.log", env=runtime_env)
            url = f"http://127.0.0.1:{port}"
            deadline = time.monotonic() + 45
            while True:
                if child.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError(f"Runtime {label} failed; inspect its private runtime.log")
                try:
                    with urllib.request.urlopen(url + "/api/web-ui/bootstrap", timeout=1) as reply:
                        if json.load(reply) == {"mode": "attached", "protocolVersion": 4, "transport": "websocket"}:
                            break
                except (OSError, ValueError):
                    time.sleep(0.1)
            secret = re.search(r"fwui_[A-Za-z0-9_]+", (directory / "runtime.log").read_text())
            if secret is None:
                raise RuntimeError("Disposable Runtime did not generate its credential")
            secret_file = directory / "secret.txt"
            secret_file.touch(mode=0o600)
            secret_file.write_text(secret.group())
            device_port = free_device_port()
            runtimes.append({"url": url, "directory": str(directory), "pid": child.pid,
                             "devicePort": device_port, "sharePort": free_port(), "mcpPort": free_port()})
        fixtures = {"site": {"url": site_url, "pid": site.pid}, "runtimes": runtimes}
        (output / "fixtures.json").write_text(json.dumps(fixtures, indent=2))
        print(str(output / "fixtures.json"), flush=True)
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        for child in children:
            if child.poll() is None:
                child.send_signal(signal.SIGINT)
        for child in children:
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
        print("Fixture processes exited", flush=True)


if __name__ == "__main__":
    main()
