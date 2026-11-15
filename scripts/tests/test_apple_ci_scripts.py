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

    def test_developer_id_requires_two_matching_identities_from_the_configured_team(self):
        self.stub("security", """
import os
team = os.environ["APPLE_TEAM_ID"]
installer_team = os.environ.get("INSTALLER_TEAM", team)
print(f'  1) AABB "Developer ID Application: Fixture ({team})"')
print(f'  2) CCDD "Developer ID Installer: Fixture ({installer_team})"')
""")
        result = self.run_script("apple-keychain.sh", "developer-id-identity")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), f'Fixture ({self.env["APPLE_TEAM_ID"]})')
        self.env["INSTALLER_TEAM"] = "OTHER12345"
        result = self.run_script("apple-keychain.sh", "developer-id-identity")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def notarize(self, status, *, app=False, with_pkg=True, signed=True, stapler_statuses=None):
        self.env["NOTARY_STATUS"] = status
        self.env["STAPLER_STATUSES"] = json.dumps(stapler_statuses or {})
        self.stub("sleep", "")
        self.env["APPLE_SIGNING_DIR"] = str(self.root)
        if signed:
            self.stub("codesign", """
import os, sys
if sys.argv[1] == "--display":
    print("Authority=Developer ID Application: Fixture", file=sys.stderr)
    print("TeamIdentifier=" + os.environ["APPLE_TEAM_ID"], file=sys.stderr)
    print("Timestamp=fixture", file=sys.stderr)
""")
        else:
            self.stub("codesign", "")
        self.stub("pkgutil", """
import os
print(" 1. Developer ID Installer: Fixture (" + os.environ["APPLE_TEAM_ID"] + ")")
""")
        self.stub("ditto", """
import pathlib, sys
pathlib.Path(sys.argv[-1]).touch()
""")
        self.stub("xcrun", '''
import json, os, pathlib, sys
calls = pathlib.Path(os.environ["CALL_LOG"])
prior = calls.read_text().splitlines() if calls.exists() else []
args = sys.argv[1:]
with calls.open("a") as log:
    log.write(json.dumps(args) + "\\n")
if args[:2] == ["notarytool", "submit"]:
    print(json.dumps({"status": os.environ["NOTARY_STATUS"], "id": "fixture-id"}))
elif args[0] == "stapler":
    statuses = json.loads(os.environ["STAPLER_STATUSES"]).get(args[1], [])
    previous_attempts = sum(json.loads(line) == args for line in prior)
    if previous_attempts < len(statuses):
        sys.exit(statuses[previous_attempts])
''')
        if app:
            packages = self.root / "FolderSpan.app"
            packages.mkdir()
        else:
            packages = self.root / "packages"
            (packages / "dmg").mkdir(parents=True)
            (packages / "dmg/FolderSpan.dmg").touch()
            if with_pkg:
                (packages / "pkg").mkdir()
                (packages / "pkg/FolderSpan.pkg").touch()
        result = self.run_script("notarize-macos.sh", str(packages))
        calls = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, calls

    def test_accepted_notarization_staples_and_validates_both_formats(self):
        result, calls = self.notarize("Accepted")
        self.assertEqual(result.returncode, 0, result.stderr)
        for command in (["notarytool", "submit"], ["stapler", "staple"], ["stapler", "validate"]):
            self.assertEqual([Path(call[2]).suffix for call in calls if call[:2] == command], [".dmg", ".pkg"])

    def test_notarizes_app_archive_and_staples_the_app_before_packaging(self):
        result, calls = self.notarize("Accepted", app=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[0][:2], ["notarytool", "submit"])
        self.assertEqual(Path(calls[0][2]).name, "FolderSpan.zip")
        self.assertEqual(calls[1:],
                         [["stapler", operation, str(self.root / "FolderSpan.app")]
                          for operation in ("staple", "validate")])
        self.assertFalse(Path(calls[0][2]).exists())

    def test_cloudkit_disconnect_retries_validation_without_resubmitting(self):
        result, calls = self.notarize("Accepted", app=True, stapler_statuses={"validate": [68, 0]})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call[:2] for call in calls], [
            ["notarytool", "submit"], ["stapler", "staple"],
            ["stapler", "validate"], ["stapler", "validate"],
        ])

    def test_persistent_cloudkit_error_fails_after_bounded_attempts(self):
        result, calls = self.notarize("Accepted", app=True, stapler_statuses={"staple": [68] * 4})
        self.assertEqual(result.returncode, 68, result.stderr)
        self.assertEqual([call[:2] for call in calls[1:]], [["stapler", "staple"]] * 4)

    def test_invalid_ticket_is_not_retried(self):
        result, calls = self.notarize("Accepted", app=True, stapler_statuses={"validate": [65]})
        self.assertEqual(result.returncode, 65, result.stderr)
        self.assertEqual([call[:2] for call in calls[1:]],
                         [["stapler", "staple"], ["stapler", "validate"]])

    def test_missing_package_format_fails_before_submission(self):
        result, calls = self.notarize("Accepted", with_pkg=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(calls)

    def test_unsigned_dmgs_are_not_submitted(self):
        result, calls = self.notarize("Accepted", signed=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Developer ID Application", result.stderr)
        self.assertFalse(calls)

    def test_invalid_notarization_fails_even_if_notarytool_exits_zero(self):
        result, calls = self.notarize("Invalid")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Apple 公证失败", result.stderr)
        self.assertEqual(calls[-1][:3], ["notarytool", "log", "fixture-id"])
        self.assertFalse(any(call[0] == "stapler" for call in calls))


if __name__ == "__main__":
    unittest.main()
