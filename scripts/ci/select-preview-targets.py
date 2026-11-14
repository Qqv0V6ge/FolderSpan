"""Emit the selected package matrices as GitHub Actions step outputs."""

import argparse
import json


# Label, runner, Gradle task, package directory and extension.
NATIVE_TARGETS = (
    ("linux-x64", "ubuntu-24.04", "packageTarGz", "targz", "tar.gz"),
    ("linux-arm64", "ubuntu-24.04-arm", "packageTarGz", "targz", "tar.gz"),
    ("windows-x64", "windows-2025", "packageMsi", "msi", "msi"),
    ("windows-arm64", "windows-11-arm", "createDistributable", "zip", "zip"),
    ("macos-x64", "macos-15-intel", "packageDmg", "dmg", "dmg"),
    ("macos-arm64", "macos-15", "packageDmg", "dmg", "dmg"),
)
WEB_TARGETS = (
    {"label": "js", "target": "js"},
    {"label": "wasm", "target": "wasmJs"},
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("preview", choices=("app", "headless"))
    parser.add_argument("target")
    args = parser.parse_args()

    native_labels = {label for label, *_ in NATIVE_TARGETS}
    native_platforms = {label.split("-")[0] for label in native_labels}
    allowed = {"all"} | native_labels | native_platforms
    if args.preview == "app":
        allowed |= {"android", "ios", "web", "web-js", "web-wasm"}
    if args.target not in allowed:
        parser.error(f"unsupported {args.preview} preview target: {args.target}")

    module = "desktopApp" if args.preview == "app" else "headlessRuntime"
    native = []
    for label, runner, task, directory, extension in NATIVE_TARGETS:
        if args.target in ("all", label, label.split("-")[0]):
            package = {
                "label": label,
                "os": runner,
                "tasks": f":app:{module}:{task}",
                "paths": f"app/{module}/build/compose/binaries/main/{directory}/*.{extension}",
            }
            if label == "windows-arm64":
                package["java_distribution"] = "microsoft"
            native.append(package)
    print("native=" + json.dumps(native))

    if args.preview == "app":
        web = [entry for entry in WEB_TARGETS if args.target in ("all", "web", f"web-{entry['label']}")]
        print("web=" + json.dumps(web))


if __name__ == "__main__":
    main()
