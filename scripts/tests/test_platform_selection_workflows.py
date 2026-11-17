import importlib.util
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
WORKFLOWS = ROOT / ".github/workflows"
SELECTOR = ROOT / "scripts/ci/select-build-targets.py"
spec = importlib.util.spec_from_file_location("select_build_targets", SELECTOR)
selector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selector)


def workflow_matrices(path):
    block = re.search(r"^          PACKAGE_MATRIX: >-\n((?: {12}[^\n]*\n)+)", path.read_text(), re.M)
    return json.loads(textwrap.dedent(block[1])) if block else {}


def platform_inputs(path):
    fields = re.findall(r"^      (build_[a-z0-9_]+):\n((?: {8}[^\n]*\n)+)", path.read_text(), re.M)
    return {name: body for name, body in fields if "type: boolean" in body}


def release_allowed(expression, results):
    expression = re.sub(r"needs\.([\w-]+)\.result", lambda match: repr(results[match[1]]), expression)
    expression = re.sub(r"startsWith\([^)]*\)", "False", expression)
    expression = expression.replace("inputs.create_draft", "True").replace("cancelled()", "False")
    expression = re.sub(r"!(?!=)", "not ", expression).replace("&&", " and ").replace("||", " or ")
    return eval(expression, {"__builtins__": {}})


class PlatformSelectionWorkflowsTest(unittest.TestCase):
    def workflows(self):
        return [path for path in WORKFLOWS.glob("build-*.yml") if workflow_matrices(path)]

    def test_platform_checkboxes_default_to_all_and_filter_single_or_multiple_platforms(self):
        self.assertTrue(self.workflows())
        for path in self.workflows():
            matrices = workflow_matrices(path)
            flags = platform_inputs(path)
            with self.subTest(workflow=path.name):
                self.assertTrue(flags)
                for body in flags.values():
                    self.assertIn("default: true", body)
                defaults = dict.fromkeys(flags, True)
                self.assertEqual(selector.select_targets(matrices, defaults, True), matrices)
                # Tag/push events have no dispatch inputs and must retain every platform.
                self.assertEqual(selector.select_targets(matrices, None, False), matrices)
                self.assertEqual(selector.select_targets(matrices, dict.fromkeys(flags, False), False), matrices)
                selections = [{flag} for flag in flags] + [{"build_linux_x64", "build_windows_arm64"}]
                for checked in selections:
                    inputs = {flag: flag in checked for flag in flags}
                    selected = selector.select_targets(matrices, inputs, True)
                    for group, entries in matrices.items():
                        expected = [entry for entry in entries if selector.platform_input(group, entry["label"]) in checked]
                        self.assertEqual(selected[group], expected, (path.name, checked, group))
                    self.assertTrue(all(selector.platform_input(group, entry["label"]) in flags
                                        for group, entries in matrices.items() for entry in entries))

    def test_empty_or_mistyped_selection_fails_without_emitting_partial_outputs(self):
        path = WORKFLOWS / "build-release.yml"
        matrices = workflow_matrices(path)
        empty = dict.fromkeys(platform_inputs(path), False)
        for inputs in (empty, {**empty, "build_linux_x64": "false"}, [], None):
            with self.subTest(inputs=inputs):
                result = subprocess.run(
                    [sys.executable, str(SELECTOR)], capture_output=True, text=True,
                    env=dict(os.environ, GITHUB_EVENT_NAME="workflow_dispatch",
                             BUILD_INPUTS=json.dumps(inputs), PACKAGE_MATRIX=json.dumps(matrices)),
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
                self.assertIn("::error::", result.stderr)

    def test_matrix_jobs_skip_empty_groups_before_expansion(self):
        for path in self.workflows():
            blocks = re.split(r"^  (?=[\w-]+:\n)", path.read_text().split("\njobs:\n", 1)[1], flags=re.M)
            for block in blocks:
                reference = re.search(r"platform: \$\{\{ fromJSON\(needs\.([\w-]+)\.outputs\.([\w_]+)\) \}\}", block)
                if reference:
                    self.assertIn(f"needs.{reference[1]}.outputs.{reference[2]} != '[]'", block)

    def test_partial_release_allows_unselected_jobs_but_rejects_failures_or_no_packages(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        release = workflow.split("\n  release:\n", 1)[1]
        expression = " ".join(re.search(r"    if: >-\n(.*?)(?=    runs-on:)", release, re.S)[1].split())
        names = set(re.findall(r"needs\.([\w-]+)\.result", expression))
        packages = names - {"prepare", "quality"}
        for selected in packages:
            results = {name: "success" if name in {"prepare", "quality", selected} else "skipped" for name in names}
            self.assertTrue(release_allowed(expression, results), selected)
            results[selected] = "failure"
            self.assertFalse(release_allowed(expression, results), selected)
        results = {name: "success" if name in {"prepare", "quality"} else "skipped" for name in names}
        self.assertFalse(release_allowed(expression, results))

    def test_partial_release_assets_do_not_require_an_unselected_ios_package(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        step = workflow.split("      - name: Prepare release assets and checksums\n", 1)[1].split("\n      - name:", 1)[0]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root / "artifacts/release-android-pro/FolderSpan.apk"
            package.parent.mkdir(parents=True)
            package.write_bytes(b"signed-apk")
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", script], cwd=root,
                env=dict(os.environ, BUILD_IOS="false"), capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual((root / "release-assets/android-pro-FolderSpan.apk").read_bytes(), b"signed-apk")
            self.assertIn("android-pro-FolderSpan.apk", (root / "release-assets/SHA256SUMS.txt").read_text())


if __name__ == "__main__":
    unittest.main()
