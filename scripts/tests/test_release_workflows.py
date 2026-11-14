import fnmatch
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOWS = Path(__file__).resolve().parents[2] / ".github" / "workflows"


def step_script(workflow, name):
    step = workflow.split(f"      - name: {name}\n", 1)[1].split("\n      - name:", 1)[0]
    body = step.split("        run: |\n", 1)[1]
    return textwrap.dedent(re.match(r"(?: {10}[^\n]*\n|\n)+", body).group())


class ReleaseWorkflowsTest(unittest.TestCase):
    def test_application_and_headless_builds_are_separate(self):
        for kind in ("release", "preview"):
            with self.subTest(kind=kind):
                application = (WORKFLOWS / f"build-{kind}.yml").read_text()
                headless = (WORKFLOWS / f"build-headless-{kind}.yml").read_text()
                self.assertNotIn("headless-runtime", application)
                self.assertNotIn(":app:headlessRuntime:", application)
                jobs = re.findall(r"^  ([\w-]+):$", headless.split("\njobs:\n", 1)[1], re.M)
                self.assertIn("headless-runtime", jobs)
                self.assertIn("web", jobs)
                self.assertNotIn("android", jobs)
                self.assertNotIn("desktop", jobs)

    def test_release_tags_trigger_only_the_matching_workflow(self):
        for file, accepted, rejected in (
            ("build-release.yml", "v1.2.3", "headless-v1.2.3"),
            ("build-headless-release.yml", "headless-v1.2.3", "v1.2.3"),
        ):
            with self.subTest(workflow=file):
                triggers = (WORKFLOWS / file).read_text().split("\npermissions:", 1)[0]
                patterns = re.findall(r'^      - "([^"]+)"$', triggers, re.M)
                self.assertTrue(any(fnmatch.fnmatchcase(accepted, p) for p in patterns))
                self.assertFalse(any(fnmatch.fnmatchcase(rejected, p) for p in patterns))

    def test_headless_version_is_normalized_and_validated_before_packaging(self):
        workflow = (WORKFLOWS / "build-headless-release.yml").read_text()
        script = step_script(workflow, "Validate version")
        for version in ("headless-v1.2.3", "1.2.3", "v1.2.3", "headless-v1.2", "1.2.3-rc1", "bad"):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "output"
                result = subprocess.run(
                    ["bash", "-e", "-o", "pipefail", "-c", script], capture_output=True, text=True,
                    env=dict(os.environ, ORG_GRADLE_PROJECT_releaseVersion=version, GITHUB_OUTPUT=str(output)),
                )
                if version in ("headless-v1.2.3", "1.2.3", "v1.2.3"):
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(output.read_text(), "tag=headless-v1.2.3\nversion=1.2.3\n")
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(output.exists())
        self.assertEqual(workflow.count(
            "      ORG_GRADLE_PROJECT_releaseVersion: ${{ needs.prepare.outputs.version }}"), 2)

    def test_headless_release_is_created_with_its_own_tag_and_title(self):
        workflow = (WORKFLOWS / "build-headless-release.yml").read_text()
        script = step_script(workflow, "Create or update draft GitHub Release")
        stub = '''
gh() {
  if [[ "$1 $2" == "release view" && "$5" == "isDraft,targetCommitish" ]]; then
    return 1
  fi
  printf '%s\\0' "$@" >> "$GH_CALL_LOG"
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "release-assets").mkdir()
            (root / "release-assets/runtime.zip").touch()
            log = root / "calls"
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", stub + script], cwd=root,
                capture_output=True, text=True,
                env=dict(os.environ, RELEASE_TAG="headless-v1.2.3", GITHUB_SHA="test-commit",
                         GH_CALL_LOG=str(log), GITHUB_STEP_SUMMARY=str(root / "summary")),
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            arguments = log.read_bytes().decode().split("\0")
            self.assertEqual(arguments[:3], ["release", "create", "headless-v1.2.3"])
            self.assertIn("FolderSpan Headless 1.2.3", arguments)
            self.assertIn("--draft", arguments)
            self.assertIn("--latest=false", arguments)


if __name__ == "__main__":
    unittest.main()
