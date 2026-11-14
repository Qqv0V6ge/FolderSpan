import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
PREVIEW_TARGETS = ROOT / "scripts" / "ci" / "select-preview-targets.py"


def select_preview(preview, target):
    result = subprocess.check_output([sys.executable, str(PREVIEW_TARGETS), preview, target], text=True)
    return {name: json.loads(value) for name, value in (line.split("=", 1) for line in result.splitlines())}


class SinglePackageWorkflowsTest(unittest.TestCase):
    def test_build_and_quality_matrices_only_include_pro(self):
        paths = list(WORKFLOWS.glob("build-*.yml")) + [WORKFLOWS / "shared-quality.yml"]
        for path in paths:
            with self.subTest(workflow=path.name):
                text = path.read_text()
                editions = re.findall(r"^\s+edition: \[([^\]]+)\]$", text, re.M)
                self.assertTrue(editions)
                self.assertTrue(all(value.strip() == "pro" for value in editions), editions)
                self.assertNotIn("packageDistributionForCurrentOS", text)
                self.assertNotIn("packageReleaseDistributionForCurrentOS", text)
                self.assertNotIn("Build Flatpak", text)

    def test_each_platform_selects_one_matching_packaging_task_and_output(self):
        entries = []
        for path in WORKFLOWS.glob("build-*.yml"):
            entries.extend(self.read_static_package_entries(path))
        for preview in ("app", "headless"):
            entries.extend(select_preview(preview, "all")["native"])

        self.assertEqual(len(entries), 24)
        for entry in entries:
            label, output, task = entry["label"], entry["paths"], entry["tasks"]
            with self.subTest(platform=label, output=output):
                module = re.search(r":app:(desktopApp|headlessRuntime):", task).group(1)
                mode = "Release" if "/main-release/" in output else ""
                if label.startswith("linux-"):
                    selected, extension = f"package{mode}TarGz", ".tar.gz"
                elif label == "windows-x64":
                    selected, extension = f"package{mode}Msi", ".msi"
                elif label == "windows-arm64":
                    selected, extension = f"create{mode}Distributable", ".zip"
                elif label.startswith("macos-"):
                    selected, extension = f"package{mode}Dmg", ".dmg"
                else:
                    self.fail(f"未指定单包策略的平台：{label}")
                self.assertEqual(task, f":app:{module}:{selected}")
                self.assertTrue(output.startswith(f"app/{module}/build/compose/binaries/"))
                self.assertTrue(output.endswith(extension))
                self.assertEqual(len(output.splitlines()), 1)

    @staticmethod
    def read_static_package_entries(path):
        for block in re.split(r"^          - os: ", path.read_text(), flags=re.M)[1:]:
            if re.search(r"^            tasks: ", block, re.M):
                yield {
                    key: re.search(rf"^            {key}: (.+)$", block, re.M).group(1)
                    for key in ("label", "paths", "tasks")
                }

    def test_previews_only_dispatch_manually_and_filter_every_selectable_target(self):
        platforms = ("linux", "windows", "macos")
        native = {f"{platform}-{arch}" for platform in platforms for arch in ("x64", "arm64")}
        for preview, file_name in (("app", "build-preview.yml"), ("headless", "build-headless-preview.yml")):
            workflow = (WORKFLOWS / file_name).read_text()
            trigger = workflow.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
            self.assertEqual(re.findall(r"^  ([\w-]+):", trigger, re.M), ["workflow_dispatch"])
            self.assertIn("default: all", trigger)
            targets = set(re.findall(r"^          - ([\w-]+)$", trigger, re.M))
            extra = {"android", "ios", "web", "web-js", "web-wasm"} if preview == "app" else set()
            self.assertEqual(targets, {"all", *platforms} | native | extra)

            for target in sorted(targets):
                with self.subTest(preview=preview, target=target):
                    selected = select_preview(preview, target)
                    self.assertCountEqual(
                        [entry["label"] for entry in selected["native"]],
                        [label for label in native if target in ("all", label, label.split("-")[0])],
                    )
                    if preview == "app":
                        self.assertCountEqual(
                            [entry["label"] for entry in selected["web"]],
                            [label for label in ("js", "wasm") if target in ("all", "web", f"web-{label}")],
                        )

            result = subprocess.run(
                [sys.executable, str(PREVIEW_TARGETS), preview, "invalid-target"], capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")

    def test_upload_guard_accepts_one_package_and_rejects_missing_or_duplicate_files(self):
        guards = 0
        for path in WORKFLOWS.glob("build-*.yml"):
            blocks = re.findall(
                r"^      - name: Verify single platform package\n.*?        run: \|\n"
                r"(.*?)(?=^      - |^  [\w-]+:|\Z)",
                path.read_text(), re.M | re.S,
            )
            for block in blocks:
                script = textwrap.dedent(block)
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    for count in range(3):
                        if count:
                            (root / f"package {count}.pkg").touch()
                        result = subprocess.run(
                            ["bash", "-e", "-o", "pipefail", "-c", script],
                            env=dict(os.environ, PACKAGE_PATH=str(root / "*.pkg")),
                            capture_output=True, text=True,
                        )
                        with self.subTest(workflow=path.name, packages=count):
                            self.assertEqual(result.returncode == 0, count == 1, result.stdout + result.stderr)
                guards += 1
        self.assertEqual(guards, 4)

    def test_android_release_only_builds_and_uploads_apk(self):
        text = (WORKFLOWS / "build-release.yml").read_text()
        self.assertIn(":app:androidApp:assembleRelease", text)
        self.assertNotIn(":app:androidApp:bundleRelease", text)
        self.assertIn("path: release-artifacts/android/*.apk", text)
        self.assertIn("mkdir -p release-artifacts/android", text)

    def test_apple_store_builds_use_pro(self):
        release = (WORKFLOWS / "build-release.yml").read_text()
        if "APPLE_STORE_EDITION:" in release:
            self.assertIn("APPLE_STORE_EDITION: pro", release)
            self.assertNotIn("vars.APPLE_STORE_EDITION", release)
        for path in WORKFLOWS.glob("apple-store-*.yml"):
            text = path.read_text()
            self.assertEqual(path.name, "apple-store-pro.yml")
            self.assertIn('ORG_GRADLE_PROJECT_includeProMain: "true"', text)
            self.assertIn("submodules: true", text)
            self.assertIn("secrets.PROMAIN_REPOSITORY_TOKEN", text)


if __name__ == "__main__":
    unittest.main()
