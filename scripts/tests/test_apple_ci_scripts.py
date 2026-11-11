import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "ci"


class AppleScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "calls.jsonl"
        self.env = dict(os.environ, RUNNER_TEMP=str(self.root),
                        GITHUB_ENV=str(self.root / "github-env"), CALL_LOG=str(self.log),
                        PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        APPLE_CERTIFICATE_BASE64="Zml4dHVyZQ==", APPLE_CERTIFICATE_PASSWORD="test-only",
                        APPLE_ID="fixture@example.invalid", APPLE_APP_SPECIFIC_PASSWORD="test-only",
                        APPLE_TEAM_ID="TG28DN3J2L")
        self.stub("security", '''
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ["CALL_LOG"], "a") as log:
    log.write(json.dumps(args) + "\\n")
if args == ["list-keychains", "-d", "user"]:
    print('"/original/login.keychain-db"')
elif args[0] == "create-keychain":
    pathlib.Path(args[-1]).touch()
elif args[0] == "delete-keychain":
    pathlib.Path(args[-1]).unlink()
elif args[0] == "import" and os.environ.get("FAIL_IMPORT"):
    sys.exit(1)
''')

    def stub(self, name, body):
        path = self.bin / name
        path.write_text("#!/usr/bin/env python3\n" + body)
        path.chmod(0o755)

    def run_script(self, name, *arguments):
        return subprocess.run(["bash", str(SCRIPTS / name), *arguments], env=self.env,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_keychain_lifecycle_restores_search_list_and_removes_only_our_profiles(self):
        setup = self.run_script("apple-keychain.sh", "setup")
        self.assertEqual(setup.returncode, 0, setup.stderr)
        signing = self.root / "folderspan-apple-signing"
        self.assertTrue((signing / "signing.keychain-db").exists())
        self.assertFalse((signing / "certificates.p12").exists())
        own = self.root / "our-profile.mobileprovision"
        unrelated = self.root / "unrelated.mobileprovision"
        own.touch()
        unrelated.touch()
        (signing / "installed-profiles.txt").write_text(str(own) + "\n")
        cleanup = self.run_script("apple-keychain.sh", "cleanup")
        self.assertEqual(cleanup.returncode, 0, cleanup.stderr)
        self.assertFalse(signing.exists())
        self.assertFalse(own.exists())
        self.assertTrue(unrelated.exists())
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertIn(["list-keychains", "-d", "user", "-s", "/original/login.keychain-db"], calls)
        self.assertEqual(self.run_script("apple-keychain.sh", "cleanup").returncode, 0)

    def test_failed_import_can_still_be_cleaned_up(self):
        self.env["FAIL_IMPORT"] = "true"
        self.assertNotEqual(self.run_script("apple-keychain.sh", "setup").returncode, 0)
        self.assertIn("APPLE_KEYCHAIN=", (self.root / "github-env").read_text())
        self.assertEqual(self.run_script("apple-keychain.sh", "cleanup").returncode, 0)
        self.assertFalse((self.root / "folderspan-apple-signing").exists())

    def notarize(self, status):
        self.env["NOTARY_STATUS"] = status
        self.env["APPLE_SIGNING_DIR"] = str(self.root)
        self.stub("codesign", "")
        self.stub("pkgutil", "")
        self.stub("xcrun", '''
import json, os, sys
with open(os.environ["CALL_LOG"], "a") as log:
    log.write(json.dumps(sys.argv[1:]) + "\\n")
if sys.argv[1:3] == ["notarytool", "submit"]:
    print(json.dumps({"status": os.environ["NOTARY_STATUS"], "id": "fixture-id"}))
''')
        packages = self.root / "packages"
        (packages / "dmg").mkdir(parents=True)
        (packages / "pkg").mkdir()
        (packages / "dmg/FolderSpan.dmg").touch()
        (packages / "pkg/FolderSpan.pkg").touch()
        result = self.run_script("notarize-macos.sh", str(packages))
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        return result, calls

    def test_accepted_notarization_staples_and_validates_both_formats(self):
        result, calls = self.notarize("Accepted")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sum(call[:2] == ["stapler", "staple"] for call in calls), 2)
        self.assertEqual(sum(call[:2] == ["stapler", "validate"] for call in calls), 2)

    def test_invalid_notarization_fails_even_if_notarytool_exits_zero(self):
        result, calls = self.notarize("Invalid")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Apple 公证失败", result.stderr)
        self.assertFalse(any(call[0] == "stapler" for call in calls))


if __name__ == "__main__":
    unittest.main()
