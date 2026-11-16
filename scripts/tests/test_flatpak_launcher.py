import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


class FlatpakLauncherTest(unittest.TestCase):
    def test_missing_or_relative_data_home_fails_before_launching_java(self):
        launcher = Path(__file__).resolve().parents[2] / "packaging/flatpak/folderspan"
        for value in (None, "", "relative/data"):
            with self.subTest(data_home=value):
                environment = dict(os.environ)
                environment.pop("XDG_DATA_HOME", None)
                if value is not None:
                    environment["XDG_DATA_HOME"] = value
                result = subprocess.run(["sh", str(launcher)], env=environment,
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(1, result.returncode)
                self.assertIn("XDG_DATA_HOME must be an absolute path", result.stderr)

    def test_preferences_use_private_data_directory_and_keep_existing_java_options(self):
        java = shutil.which("java")
        if java is None:
            self.skipTest("Java is required")
        launcher = Path(__file__).resolve().parents[2] / "packaging/flatpak/folderspan"
        with tempfile.TemporaryDirectory(prefix="folderspan flatpak ") as temporary:
            root = Path(temporary)
            script = root / "launcher"
            script.write_text(launcher.read_text().replace("/app/folderspan/bin/FolderSpan", shlex.quote(java)))
            for options in ("", "-Dflatpak.launcher.probe=kept"):
                with self.subTest(options=options):
                    environment = dict(os.environ, XDG_DATA_HOME=str(root / "data"), JAVA_TOOL_OPTIONS=options)
                    environment.pop("_JAVA_OPTIONS", None)
                    environment.pop("JDK_JAVA_OPTIONS", None)
                    result = subprocess.run(
                        ["sh", str(script), "-XshowSettings:properties", "-version"],
                        env=environment, capture_output=True, text=True, timeout=15,
                    )
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn(f"java.util.prefs.userRoot = {root / 'data/FolderSpan'}", result.stderr)
                    if options:
                        self.assertIn("flatpak.launcher.probe = kept", result.stderr)


if __name__ == "__main__":
    unittest.main()
