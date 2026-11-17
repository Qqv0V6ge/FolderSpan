"""Filter package matrices using the workflow's platform checkboxes."""

import json
import os
import sys


def platform_input(group, label):
    prefix = "web_" if group == "web" else ""
    return "build_" + prefix + label.replace("-", "_")


def select_targets(matrices, inputs, manual):
    if not manual:
        return matrices
    if not isinstance(inputs, dict):
        raise ValueError("Build inputs must be an object.")
    names = {platform_input(group, entry["label"]) for group, entries in matrices.items() for entry in entries}
    names.update({"build_android", "build_ios", "build_macos_store"} & inputs.keys())
    for name in names:
        if name in inputs and type(inputs[name]) is not bool:
            raise ValueError(f"{name} must be a checkbox boolean.")
    if not any(inputs.get(name, False) for name in names):
        raise ValueError("Select at least one packaging platform.")
    return {
        group: [entry for entry in entries if inputs.get(platform_input(group, entry["label"]), False)]
        for group, entries in matrices.items()
    }


def main():
    matrices = json.loads(os.environ["PACKAGE_MATRIX"])
    inputs = json.loads(os.environ.get("BUILD_INPUTS", "{}"))
    selected = select_targets(matrices, inputs, os.environ["GITHUB_EVENT_NAME"] == "workflow_dispatch")
    for group, entries in selected.items():
        print(f"{group}=" + json.dumps(entries, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, TypeError) as error:
        sys.exit(f"::error::{error}")
