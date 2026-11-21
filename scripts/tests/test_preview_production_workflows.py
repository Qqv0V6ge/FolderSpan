import base64
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

from test_platform_selection_workflows import workflow_matrices


WORKFLOWS = Path(__file__).resolve().parents[2] / ".github/workflows"


def step_block(workflow, name):
    block = workflow.split(f"      - name: {name}\n", 1)[1]
    return re.split(r"^      - name: |^  [\w-]+:\n", block, maxsplit=1, flags=re.M)[0]


def step_script(block):
    body = block.split("        run: |\n", 1)[1]
    return textwrap.dedent(re.match(r"(?: {10}[^\n]*\n|\n)+", body)[0])


class PreviewProductionWorkflowsTest(unittest.TestCase):
    def test_every_preview_uses_production_builds_and_matching_artifact_paths(self):
        paths = [WORKFLOWS / "build-preview.yml"]
        for path in paths:
            workflow = path.read_text()
            with self.subTest(workflow=path.name):
                self.assertRegex(workflow, r"(?m)^  ORG_GRADLE_PROJECT_folderspanBuildType: release$")
                self.assertIn("BrowserDistribution", workflow)
                self.assertIn("/productionExecutable", workflow)
                for development in ("assembleDebug", "BrowserDevelopment", "/developmentExecutable", "binaries/main/"):
                    self.assertNotIn(development, workflow)
                for group, entries in workflow_matrices(path).items():
                    if group == "web":
                        continue
                    for entry in entries:
                        self.assertIn("/main-release/", entry["paths"])
                        for task in entry["tasks"].split():
                            self.assertRegex(task, r":(?:packageRelease\w+|createReleaseDistributable)$")
        android = (WORKFLOWS / "build-preview.yml").read_text()
        self.assertIn(":app:androidApp:assembleRelease", android)
        self.assertIn("path: app/androidApp/build/outputs/apk/release/*.apk", android)
        if "  pull_request:" in android:
            job = android.split("\n  android:\n", 1)[1].split("\n  web:\n", 1)[0]
            self.assertIn("github.event_name != 'pull_request'", job)

    def test_android_preview_reuses_release_signing_and_cleans_up_temporary_key(self):
        preview = (WORKFLOWS / "build-preview.yml").read_text()
        release = (WORKFLOWS / "build-release.yml").read_text()
        restore = step_block(preview, "Validate and restore Android release signing")
        cleanup = step_block(preview, "Remove temporary Android keystore")
        self.assertEqual(restore.strip(), step_block(release, "Validate and restore Android release signing").strip())
        self.assertEqual(cleanup.strip(), step_block(release, "Remove temporary Android keystore").strip())
        self.assertIn("if: always()", cleanup)
        signed_build = step_block(preview, "Build signed preview APK")
        keys = ("ANDROID_KEYSTORE_BASE64", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
        for name in keys[1:]:
            self.assertIn(f"{name}: ${{{{ secrets.{name} }}}}", signed_build)
        fixture = b"workflow signing fixture"
        for missing in (*keys, None):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                values = dict.fromkeys(keys, "test-signing-value")
                values[keys[0]] = base64.b64encode(fixture).decode()
                if missing:
                    values[missing] = ""
                environment = dict(os.environ, **values, RUNNER_TEMP=directory, GITHUB_ENV=str(root / "environment"))
                result = subprocess.run(
                    ["bash", "-e", "-o", "pipefail", "-c", step_script(restore)],
                    env=environment, capture_output=True, text=True,
                )
                if missing:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(missing, result.stdout)
                    self.assertFalse((root / "folderspan-release.keystore").exists())
                else:
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    key = root / "folderspan-release.keystore"
                    self.assertEqual(key.read_bytes(), fixture)
                    self.assertEqual(key.stat().st_mode & 0o777, 0o600)
                    self.assertIn(f"ANDROID_STORE_FILE={key}", (root / "environment").read_text())
                    subprocess.run(
                        ["bash", "-e", "-o", "pipefail", "-c", step_script(cleanup)],
                        env=dict(environment, ANDROID_STORE_FILE=str(key)), check=True,
                    )
                    self.assertFalse(key.exists())


if __name__ == "__main__":
    unittest.main()
