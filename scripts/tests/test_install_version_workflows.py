import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/resolve-install-versions.py"
spec = importlib.util.spec_from_file_location("install_versions", SCRIPT)
versions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(versions)


def components(value):
    return tuple(map(int, value.split(".")))


class InstallVersionWorkflowsTest(unittest.TestCase):
    def test_release_upgrades_the_same_preview_revision_on_every_platform(self):
        preview = versions.derive_versions("1.1.2", 89, "preview")
        release = versions.derive_versions("1.1.2", 89, "release")
        self.assertEqual(preview["version"], release["version"])
        self.assertEqual(preview["source_revision"], release["source_revision"])
        self.assertGreater(int(release["android_version_code"]), int(preview["android_version_code"]))
        for field in ("windows_package_version", "apple_build_number", "apple_no_pro_build_number"):
            self.assertGreater(components(release[field]), components(preview[field]))
        self.assertGreater(components(release["apple_no_pro_build_number"]), components(release["apple_build_number"]))
        # Published packages used workflow counters and semantic versions through v1.1.1.
        self.assertGreater(int(preview["android_version_code"]), 40)
        self.assertGreater(components(preview["windows_package_version"]), (1, 0, 40))
        self.assertGreater(components(preview["windows_package_version"]), (1, 1, 1))
        self.assertGreater(components(preview["apple_build_number"]), (40,))

    def test_next_source_revision_is_newer_even_when_returning_to_preview(self):
        for revision in (24, 89, 2499, 24999, 32766):
            release = versions.derive_versions("1.1.2", revision, "release")
            preview = versions.derive_versions("1.1.2", revision + 1, "preview")
            with self.subTest(revision=revision):
                self.assertGreater(int(preview["android_version_code"]), int(release["android_version_code"]))
                for field in ("windows_package_version", "apple_build_number", "apple_no_pro_build_number"):
                    self.assertGreater(components(preview[field]), components(release[field]))
                self.assertGreater(components(preview["apple_build_number"]), components(release["apple_no_pro_build_number"]))

    def test_platform_limits_fail_before_versions_can_wrap_or_overflow(self):
        result = versions.derive_versions("1.255.999", 32767, "release")
        self.assertEqual(components(result["windows_package_version"]), (1, 255, 65535))
        self.assertLessEqual(int(result["android_version_code"]), 2_100_000_000)
        for field in ("apple_build_number", "apple_no_pro_build_number"):
            major, minor, patch = components(result[field])
            self.assertTrue(1 <= major <= 9999 and 0 <= minor <= 99 and 0 <= patch <= 99)
        for version, revision, channel in (
            ("1.1.2", 32768, "release"), ("1.1.2", 0, "preview"),
            ("1.1.2", 1, "dev"), ("0.1.2", 1, "preview"),
            ("1.256.2", 1, "preview"), ("128.0.0", 1, "release"),
            ("127.255.0", 1, "release"), ("1.2", 1, "release"),
            ("1.1.65536", 1, "release"),
        ):
            with self.subTest(version=version, revision=revision, channel=channel):
                with self.assertRaises(ValueError):
                    versions.derive_versions(version, revision, channel)

    def test_cli_ignores_preview_40_release_20_and_is_stable_on_rerun(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)

            def commit():
                subprocess.run(["git", "-c", "user.name=Version test", "-c", "user.email=version@example.test",
                                "commit", "--quiet", "--allow-empty", "-m", "Source"], cwd=root, check=True)

            def resolve(channel, number, version="1.1.2", cwd=root):
                return subprocess.run(
                    [sys.executable, str(SCRIPT)], cwd=cwd, capture_output=True, text=True,
                    env=dict(os.environ, BUILD_CHANNEL=channel, BUILD_VERSION=version,
                             GITHUB_RUN_NUMBER=str(number)),
                )

            def outputs(result):
                self.assertEqual(result.returncode, 0, result.stderr)
                return dict(line.split("=", 1) for line in result.stdout.splitlines())

            for _ in range(3):
                commit()
            subprocess.run(["git", "tag", "v1.1.1"], cwd=root, check=True)
            subprocess.run(["git", "tag", "archive/v99.0.0"], cwd=root, check=True)
            subprocess.run(["git", "tag", "headless-v2.0.0"], cwd=root, check=True)
            preview = outputs(resolve("preview", 40))
            release = outputs(resolve("release", 20))
            self.assertEqual(release["source_revision"], "3")
            self.assertEqual(release, outputs(resolve("release", 200)))
            self.assertGreater(int(release["android_version_code"]), int(preview["android_version_code"]))
            self.assertEqual(components(release["windows_package_version"]), (1, 1, 7))
            self.assertEqual(outputs(resolve("preview", 40, version=""))["version"], "1.1.1")
            missing_version = resolve("release", 20, version="")
            self.assertNotEqual(missing_version.returncode, 0)
            self.assertEqual(missing_version.stdout, "")
            self.assertIn("explicit release version", missing_version.stderr)
            old = resolve("release", 100, "1.0.99")
            self.assertNotEqual(old.returncode, 0)
            self.assertEqual(old.stdout, "")
            self.assertIn("published baseline", old.stderr)
            commit()
            newer_preview = outputs(resolve("preview", 1))
            self.assertGreater(int(newer_preview["android_version_code"]), int(release["android_version_code"]))
            shallow = root / "shallow"
            subprocess.run(["git", "clone", "--quiet", "--depth", "1", root.as_uri(), str(shallow)], check=True)
            result = resolve("preview", 40, cwd=shallow)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")
            self.assertIn("full Git history", result.stderr)

    def test_all_package_workflows_use_the_shared_resolver_and_forward_install_versions(self):
        paths = list((ROOT / ".github/workflows").glob("build-*.yml"))
        self.assertTrue(paths)
        for path in paths:
            text = path.read_text()
            with self.subTest(workflow=path.name):
                self.assertNotIn("github.run_number", text)
                self.assertIn("fetch-depth: 0", text)
                self.assertIn("python3 scripts/ci/resolve-install-versions.py", text)
                self.assertIn("outputs.windows_package_version", text)
                self.assertRegex(text, r"ORG_GRADLE_PROJECT_windowsPackageVersion: \$\{\{ needs\.(?:prepare|targets)\.outputs\.windows_package_version \}\}")
                if path.name in ("build-preview.yml", "build-release.yml"):
                    self.assertRegex(text, r"ORG_GRADLE_PROJECT_androidVersionCode: \$\{\{ needs\.(?:prepare|targets)\.outputs\.android_version_code \}\}")
        gradle = (ROOT / "app/desktopApp/build.gradle.kts").read_text()
        self.assertIn('gradleProperty("windowsPackageVersion")', gradle)
        self.assertIn("msiPackageVersion = installVersion", gradle)
        self.assertIn("exePackageVersion = installVersion", gradle)


if __name__ == "__main__":
    unittest.main()
