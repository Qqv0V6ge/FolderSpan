#!/usr/bin/env python3
"""Check an actual packaged GUI opens a visible window under Xvfb, without a build."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.command:
        parser.error("an executable is required")
    output = Path(os.environ.get("DISTRIBUTION_RESULTS", "distribution-results")).resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = {"label": args.label, "command": args.command,
              "scope": "Visible desktop window smoke check, not full feature or sandbox certification"}
    existing = set(subprocess.run(["xdotool", "search", "--onlyvisible", "--name", "FolderSpan"],
                                 capture_output=True, text=True, timeout=5).stdout.splitlines())
    with (output / f"{args.label}.log").open("w") as log:
        process = subprocess.Popen(args.command, stdout=log, stderr=log, start_new_session=True)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"Application exited before opening a window: {process.returncode}")
                windows = subprocess.run(
                    ["xdotool", "search", "--onlyvisible", "--name", "FolderSpan"],
                    capture_output=True, text=True, timeout=5)
                new_windows = set(windows.stdout.splitlines()) - existing
                if windows.returncode == 0 and new_windows:
                    report["window_ids"] = sorted(new_windows)
                    subprocess.run(["import", "-window", "root", str(output / f"{args.label}.png")],
                                   check=True, timeout=10)
                    report["passed"] = True
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("No visible FolderSpan window within 60 seconds")
        except Exception as exc:
            report.update(passed=False, error=str(exc))
            raise
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            (output / f"{args.label}.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
