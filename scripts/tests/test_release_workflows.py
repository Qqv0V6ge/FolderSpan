import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def step_script(workflow, name):
    step = workflow.split(f"      - name: {name}\n", 1)[1].split("\n      - name:", 1)[0]
    body = step.split("        run: |\n", 1)[1]
    return textwrap.dedent(re.match(r"(?: {10}[^\n]*\n|\n)+", body).group())


class ReleaseWorkflowsTest(unittest.TestCase):
    def test_application_release_restores_both_editions_and_platform_formats(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        self.assertEqual(re.findall(r"^        edition: (.*)$", workflow, re.M), ["[pro, no-pro]"] * 4)
        self.assertIn(":app:androidApp:bundleRelease", workflow)
        self.assertIn("-PandroidEnableAbiSplits=false", workflow)
        self.assertIn("outputs/bundle/release/*.aab release-artifacts/android/", workflow)
        self.assertIn("outputs/apk/release/*.apk release-artifacts/android/", workflow)
        self.assertIn("path: release-artifacts/android/*", workflow)
        desktop = workflow.split("\n  desktop:\n", 1)[1].split("\n    env:", 1)[0]
        expected = {
            "linux-x64": {".deb", ".rpm", ".tar.gz", ".flatpak"},
            "linux-arm64": {".deb", ".rpm", ".tar.gz", ".flatpak"},
            "windows-x64": {".msi", ".exe"},
            "windows-arm64": {".zip"},
            "macos-x64": {".dmg", ".pkg"},
            "macos-arm64": {".dmg", ".pkg"},
        }
        for label, formats in expected.items():
            with self.subTest(platform=label):
                entry = desktop.split(f"label: {label}\n", 1)[1].split("          - os: ", 1)[0]
                self.assertEqual(set(re.findall(r"\*\.(\w+(?:\.gz)?)$", entry, re.M)),
                                 {extension[1:] for extension in formats})
        for extra in ("release-linux-channel-${{ matrix.edition }}", "release-chocolatey-${{ matrix.edition }}",
                      "release-channel-recipes"):
            self.assertIn(extra, workflow)

    def test_desktop_release_requires_every_format(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        script = step_script(workflow, "Verify desktop package formats")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for expected_success in (False, True):
                (root / "package.deb").write_bytes(b"deb")
                if expected_success:
                    (root / "package.rpm").write_bytes(b"rpm")
                result = subprocess.run(
                    ["bash", "-e", "-o", "pipefail", "-c", script],
                    env=dict(os.environ, PACKAGE_PATHS=f"{root}/*.deb\n{root}/*.rpm"),
                    capture_output=True, text=True,
                )
                self.assertEqual(result.returncode == 0, expected_success, result.stderr + result.stdout)

    def test_channel_recipes_use_the_current_build_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for platform, edition, name, content in (
                ("windows-x64", "no-pro", "FolderSpan-1.2.3.msi", b"windows-x64"),
                ("windows-x64", "pro", "FolderSpan-1.2.3.msi", b"windows-x64-pro"),
                ("windows-arm64", "no-pro", "FolderSpan-1.2.3-windows-arm64.zip", b"windows-arm64"),
                ("linux-x64", "no-pro", "com.folderspan-1.2.3.tar.gz", b"linux-x64"),
            ):
                target = root / f"release-desktop-{platform}-{edition}" / name
                target.parent.mkdir()
                target.write_bytes(content)
            msiinfo = root / "msiinfo"
            msiinfo.write_text("#!/bin/sh\nprintf 'ProductCode\\t{PRODUCT}\\nUpgradeCode\\t{UPGRADE}\\n'\n")
            msiinfo.chmod(0o755)
            commit = "a" * 40
            subprocess.run(
                [sys.executable, str(ROOT / "scripts/ci/prepare-release-channel-recipes.py"),
                 "recipes", "1.2.3", commit, "42", str(root), str(root)],
                env=dict(os.environ, PATH=f"{root}{os.pathsep}{os.environ['PATH']}"), check=True,
            )
            with ZipFile(root / "FolderSpan-1.2.3-channel-recipes.zip") as bundle:
                self.assertTrue({"aur/.SRCINFO", "aur/folderspan.desktop", "nix/package.nix"} <= set(bundle.namelist()))
                installer = bundle.read("winget/FolderSpan.FolderSpan.installer.yaml").decode()
                self.assertIn("releases/download/v1.2.3/", installer)
                self.assertIn(hashlib.sha256(b"windows-x64").hexdigest().upper(), installer)
                self.assertIn("{PRODUCT}", installer)
                self.assertIn("{UPGRADE}", installer)
                scoop = json.loads(bundle.read("scoop/bucket/folderspan.json"))
                self.assertEqual(scoop["architecture"]["arm64"]["hash"], hashlib.sha256(b"windows-arm64").hexdigest())
                fdroid = bundle.read("fdroid/metadata/com.folderspan.yml").decode()
                self.assertIn("Disabled:", fdroid)
                self.assertIn(f"commit: {commit}", fdroid)
                self.assertIn("androidVersionCode=42", fdroid)
                for channel, edition, package_id, content in (
                    ("chocolatey", "no-pro", "folderspan", b"windows-x64"),
                    ("chocolatey-pro", "pro", "folderspan-pro", b"windows-x64-pro"),
                ):
                    nuspec = bundle.read(f"{channel}/folderspan.nuspec").decode()
                    installer = bundle.read(f"{channel}/tools/chocolateyInstall.ps1").decode()
                    self.assertIn(f"<id>{package_id}</id>", nuspec)
                    self.assertIn(f"desktop-windows-x64-{edition}-FolderSpan-1.2.3.msi", installer)
                    self.assertIn(hashlib.sha256(content).hexdigest().upper(), installer)
                    if edition == "pro":
                        self.assertIn("/blob/v1.2.3/LICENSING.md", nuspec)
                        self.assertNotIn("permanently free", nuspec)
                for recipe in ("aur/PKGBUILD", "aur/.SRCINFO", "nix/package.nix"):
                    text = bundle.read(recipe).decode()
                    self.assertIn(hashlib.sha256(b"linux-x64").hexdigest(), text)
                    self.assertNotIn("THIRD_PARTY_NOTICES-2026-10-27", text)
                subprocess.run(["bash", "-n"], input=bundle.read("aur/PKGBUILD"), check=True)

    def test_snap_stages_the_matching_edition_and_license(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        script = step_script(workflow, "Prepare strict Snap from the app image")
        for edition, name, license_type in (
            ("no-pro", "folderspan", "Apache-2.0"),
            ("pro", "folderspan-pro", "Proprietary"),
        ):
            with self.subTest(edition=edition), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                app = root / "app/desktopApp/build/compose/binaries/main-release/app/FolderSpan"
                app.mkdir(parents=True)
                (app / "launcher").write_text(edition)
                for source in ("packaging/snap/snapcraft.yaml", "packaging/linux/folderspan.desktop"):
                    target = root / source
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / source, target)
                subprocess.run(
                    ["bash", "-e", "-o", "pipefail", "-c", script], cwd=root, check=True,
                    env=dict(os.environ, ORG_GRADLE_PROJECT_releaseVersion="v1.2.3", PACKAGE_EDITION=edition),
                )
                project = root / "build/release-snap"
                content = (project / "snapcraft.yaml").read_text()
                self.assertIn(f"name: {name}\n", content)
                self.assertIn(f"apps:\n  {name}:\n", content)
                self.assertIn(f"license: {license_type}\n", content)
                self.assertIn("version: '1.2.3'", content)
                self.assertIn("source: local/application", content)
                self.assertNotIn("v1.0.1", content)
                self.assertEqual((project / "local/application/launcher").read_text(), edition)
                if edition == "pro":
                    self.assertNotIn("Apache-2.0", content)

    def test_application_checks_remote_release_tag_before_packaging(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        prepare, remaining = workflow.split("\n  quality:\n", 1)
        self.assertIn("Verify remote tag before packaging", prepare)
        self.assertIn("    needs: prepare\n", remaining)
        self.assertIn("if: startsWith(github.ref, 'refs/tags/v') || inputs.create_draft", prepare)
        script = step_script(prepare, "Verify remote tag before packaging")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True).strip()

            git("init", "--quiet")
            git("-c", "user.name=Test", "-c", "user.email=test@example.test",
                "commit", "--quiet", "--allow-empty", "-m", "Release")
            current_sha = git("rev-parse", "HEAD")
            git("tag", "v1.2.3")
            git("-c", "user.name=Test", "-c", "user.email=test@example.test",
                "tag", "-a", "v1.2.4", "-m", "Annotated release")
            git("remote", "add", "origin", str(root))
            for tag, sha, should_succeed in (
                ("v1.2.5", current_sha, True),
                ("v1.2.3", current_sha, True),
                ("v1.2.4", current_sha, True),
                ("v1.2.3", "other-commit", False),
                ("v1.2.4", "other-commit", False),
            ):
                with self.subTest(tag=tag, sha=sha):
                    result = subprocess.run(
                        ["bash", "-e", "-o", "pipefail", "-c", script], cwd=root,
                        capture_output=True, text=True,
                        env=dict(os.environ, RELEASE_TAG=tag, GITHUB_SHA=sha),
                    )
                    self.assertEqual(result.returncode == 0, should_succeed, result.stderr + result.stdout)
                    if not should_succeed:
                        self.assertIn(tag, result.stdout)
                        self.assertIn(current_sha, result.stdout)
                        self.assertIn(sha, result.stdout)

    def test_application_rebuild_retargets_only_an_untagged_draft(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        script = step_script(workflow, "Create or update draft GitHub Release")
        stub = """\
git() { [[ "$EXISTING_TAG" == true ]]; }
gh() {
  if [[ "$1 $2" == "release view" && "$5" == "isDraft,targetCommitish" ]]; then
    printf '%s\\t%s\\n' "$IS_DRAFT" old-commit
    return
  fi
  printf '%s\\n' "$*" >> "$GH_CALL_LOG"
}
"""
        for is_draft, tag_exists in (("true", "false"), ("true", "true"), ("false", "true")):
            with self.subTest(draft=is_draft, tag=tag_exists), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "release-assets").mkdir()
                (root / "release-assets/package").write_bytes(b"package")
                calls = root / "calls"
                result = subprocess.run(
                    ["bash", "-e", "-o", "pipefail", "-c", stub + script], cwd=root,
                    capture_output=True, text=True,
                    env=dict(os.environ, RELEASE_TAG="v1.2.3", GITHUB_SHA="new-commit", IS_DRAFT=is_draft,
                             EXISTING_TAG=tag_exists, GH_CALL_LOG=str(calls),
                             GITHUB_STEP_SUMMARY=str(root / "summary")),
                )
                self.assertEqual(result.returncode == 0, is_draft == "true", result.stderr + result.stdout)
                if is_draft == "true":
                    self.assertIn("release upload v1.2.3 release-assets/package --clobber", calls.read_text())
                    self.assertEqual("release edit v1.2.3 --target new-commit" in calls.read_text(), tag_exists == "false")
                else:
                    self.assertFalse(calls.exists())

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

    def test_application_ios_workflows_reuse_signing_and_route_ipa(self):
        preview = (WORKFLOWS / "build-preview.yml").read_text()
        release = (WORKFLOWS / "build-release.yml").read_text()
        apple = (WORKFLOWS / "apple-store-pro.yml").read_text()
        for kind, workflow in (("preview", preview), ("release", release)):
            ios = workflow.split("\n  ios:\n", 1)[1].split("\n  web:\n", 1)[0]
            with self.subTest(workflow=kind):
                self.assertIn("uses: ./.github/workflows/apple-store-pro.yml", ios)
                artifact_name = "preview-ios-pro" if kind == "preview" else "release-ios-${{ matrix.edition }}"
                self.assertIn(f"ipa_artifact_name: {artifact_name}", ios)
                self.assertNotIn("APPLE_APP_SPECIFIC_PASSWORD", ios)
        self.assertIn("inputs.target == 'all' || inputs.target == 'ios'", preview)
        self.assertIn("version: ${{ needs.prepare.outputs.version }}", release)
        self.assertIn("edition: ${{ matrix.edition }}", release.split("\n  ios:\n", 1)[1].split("\n  web:\n", 1)[0])
        self.assertIn("format('{0}.1', github.run_number)", release)
        self.assertIn("      - ios\n", release.split("\n  release:\n", 1)[1])
        self.assertIn("needs.ios.result == 'success'", release)
        self.assertIn("path: ${{ env.APPLE_PACKAGE }}", apple)
        self.assertIn("inputs.ipa_artifact_name == '' && inputs.upload_to_app_store", apple)

    def test_application_release_stages_exactly_one_ipa_per_edition_with_checksums(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        script = step_script(workflow, "Prepare release assets and checksums")
        for pro, ordinary in ((0, 1), (1, 0), (1, 1), (2, 1), (1, 2)):
            with self.subTest(pro=pro, ordinary=ordinary), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for edition, count in (("pro", pro), ("no-pro", ordinary)):
                    ios = root / f"artifacts/release-ios-{edition}"
                    ios.mkdir(parents=True)
                    for index in range(count):
                        (ios / f"FolderSpan-{index}.ipa").write_bytes(edition.encode())
                result = subprocess.run(
                    ["bash", "-e", "-o", "pipefail", "-c", script], cwd=root, capture_output=True, text=True,
                )
                self.assertEqual(result.returncode == 0, (pro, ordinary) == (1, 1), result.stdout + result.stderr)
                if (pro, ordinary) == (1, 1):
                    assets = root / "release-assets"
                    for edition in ("pro", "no-pro"):
                        package = f"ios-{edition}-FolderSpan-0.ipa"
                        self.assertEqual((assets / package).read_bytes(), edition.encode())
                        self.assertIn(package, (assets / "SHA256SUMS.txt").read_text())

    def test_application_release_rejects_a_duplicate_channel_filename(self):
        workflow = (WORKFLOWS / "build-release.yml").read_text()
        script = step_script(workflow, "Prepare release assets and checksums")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for edition in ("pro", "no-pro"):
                for artifact, filename in (("ios", "FolderSpan.ipa"), ("linux-channel", "FolderSpan.AppImage")):
                    target = root / f"artifacts/release-{artifact}-{edition}" / filename
                    target.parent.mkdir(parents=True)
                    target.write_bytes(edition.encode())
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", script], cwd=root, capture_output=True, text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Duplicate release asset name", result.stdout)

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
