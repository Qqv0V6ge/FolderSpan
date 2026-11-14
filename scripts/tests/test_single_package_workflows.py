import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOWS = Path(__file__).resolve().parents[2] / ".github" / "workflows"


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
        entries = 0
        for path in WORKFLOWS.glob("build-*.yml"):
            for block in re.split(r"^          - os: ", path.read_text(), flags=re.M)[1:]:
                task_match = re.search(r"^            tasks: (.+)$", block, re.M)
                if not task_match:
                    continue
                label = re.search(r"^            label: (.+)$", block, re.M).group(1)
                output = re.search(r"^            paths: (.+)$", block, re.M).group(1)
                task = task_match.group(1)
                with self.subTest(workflow=path.name, platform=label, output=output):
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
                entries += 1
        self.assertEqual(entries, 24)

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
