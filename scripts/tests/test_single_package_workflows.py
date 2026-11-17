import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

from test_platform_selection_workflows import workflow_matrices


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


class SinglePackageWorkflowsTest(unittest.TestCase):
    def test_build_and_quality_matrices_only_include_pro(self):
        paths = [
            path for path in WORKFLOWS.glob("build-*.yml") if path.name != "build-release.yml"
        ] + [WORKFLOWS / "shared-quality.yml"]
        for path in paths:
            with self.subTest(workflow=path.name):
                text = path.read_text()
                editions = re.findall(r"^\s+edition: \[([^\]]+)\]$", text, re.M)
                self.assertTrue(editions)
                self.assertTrue(all(value.strip() == "pro" for value in editions), editions)
                self.assertNotIn("packageDistributionForCurrentOS", text)
                self.assertNotIn("packageReleaseDistributionForCurrentOS", text)
                self.assertNotIn("Build Flatpak", text)

    def test_each_platform_selects_matching_packaging_tasks_and_outputs(self):
        entries = []
        for path in WORKFLOWS.glob("build-*.yml"):
            if path.name != "build-release.yml":
                entries.extend(dict(entry, workflow=path.name) for entry in self.read_static_package_entries(path))

        self.assertEqual(len(entries), 18)
        for entry in entries:
            label, output, task = entry["label"], entry["paths"], entry["tasks"]
            with self.subTest(platform=label, output=output):
                module = re.search(r":app:(desktopApp|headlessRuntime):", task).group(1)
                mode = "Release" if "/main-release/" in output else ""
                if label.startswith("linux-"):
                    selected = [(f"package{mode}TarGz", ".tar.gz")]
                elif label == "windows-x64":
                    selected = [(f"package{mode}Msi", ".msi")]
                elif label == "windows-arm64":
                    selected = [(f"create{mode}Distributable", ".zip")]
                elif label.startswith("macos-"):
                    selected = [(f"package{mode}Dmg", ".dmg")]
                    if module == "headlessRuntime" and entry["workflow"] == "build-headless-release.yml":
                        selected.append(("packageReleasePkg", ".pkg"))
                else:
                    self.fail(f"未指定打包策略的平台：{label}")
                self.assertEqual(task.split(), [f":app:{module}:{name}" for name, _ in selected])
                outputs = output.splitlines()
                self.assertEqual(len(outputs), len(selected))
                for pattern, (_, extension) in zip(outputs, selected):
                    self.assertTrue(pattern.startswith(f"app/{module}/build/compose/binaries/"))
                    self.assertTrue(pattern.endswith(extension))

    @staticmethod
    def read_static_package_entries(path):
        for group, entries in workflow_matrices(path).items():
            if group != "web":
                yield from entries

    def test_previews_only_dispatch_manually(self):
        for file_name in ("build-preview.yml", "build-headless-preview.yml"):
            workflow = (WORKFLOWS / file_name).read_text()
            trigger = workflow.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
            self.assertEqual(re.findall(r"^  ([\w-]+):", trigger, re.M), ["workflow_dispatch"])

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
        self.assertEqual(guards, 3)

    def test_apple_store_builds_default_to_pro_and_only_pro_fetches_the_submodule(self):
        release = (WORKFLOWS / "build-release.yml").read_text()
        if "APPLE_STORE_EDITION:" in release:
            self.assertIn("APPLE_STORE_EDITION: pro", release)
            self.assertNotIn("vars.APPLE_STORE_EDITION", release)
        for path in WORKFLOWS.glob("apple-store-*.yml"):
            text = path.read_text()
            self.assertEqual(path.name, "apple-store-pro.yml")
            edition_check = "${{ (inputs.edition || 'pro') == 'pro' }}"
            self.assertIn("default: pro", text)
            self.assertIn(f"ORG_GRADLE_PROJECT_includeProMain: {edition_check}", text)
            self.assertIn(f"submodules: {edition_check}", text)
            self.assertIn("secrets.PROMAIN_REPOSITORY_TOKEN", text)


if __name__ == "__main__":
    unittest.main()
