import base64
import datetime
import importlib.util
import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "apple_profiles", Path(__file__).resolve().parents[1] / "ci/prepare-apple-profiles.py"
)
profiles = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(profiles)
TEAM = "TG28DN3J2L"
APP = "com.folderspan.FolderSpan"


def make_profile(bundle_id=APP, platform="iOS", uuid="11111111-2222-3333-4444-555555555555"):
    return {
        "UUID": uuid, "TeamIdentifier": [TEAM], "Platform": [platform],
        "ExpirationDate": datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None) + datetime.timedelta(days=1),
        "Entitlements": {"application-identifier": f"{TEAM}.{bundle_id}", "get-task-allow": False},
    }


class AppleProfilesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.signing = self.root / "signing"
        self.signing.mkdir()
        environment = {"APPLE_SIGNING_DIR": str(self.signing), "APPLE_TEAM_ID": TEAM}
        for key in ["IOS_APP_PROFILE_BASE64", "IOS_SHARE_PROFILE_BASE64",
                    "MACOS_APP_PROFILE_BASE64", "MACOS_RUNTIME_PROFILE_BASE64", "TEST_PROFILE"]:
            environment[key] = base64.b64encode(b"synthetic cms fixture").decode()
        self.environment = patch.dict(os.environ, environment)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        home = patch.object(Path, "home", return_value=self.root / "home")
        home.start()
        self.addCleanup(home.stop)

    def read(self, profile):
        with patch.object(profiles.subprocess, "check_output", return_value=plistlib.dumps(profile)):
            return profiles.read_profile("TEST_PROFILE", "profile.mobileprovision", APP, "iOS")

    def test_accepts_matching_distribution_profile(self):
        self.assertEqual(self.read(make_profile())["TeamIdentifier"], [TEAM])

    def test_rejects_wrong_team(self):
        profile = make_profile()
        profile["TeamIdentifier"] = ["OTHERTEAM00"]
        with self.assertRaisesRegex(ValueError, "团队或 Bundle ID"):
            self.read(profile)

    def test_rejects_wrong_bundle(self):
        with self.assertRaisesRegex(ValueError, "Bundle ID"):
            self.read(make_profile(APP + ".other"))

    def test_rejects_wrong_platform(self):
        with self.assertRaisesRegex(ValueError, "平台"):
            self.read(make_profile(platform="OSX"))

    def test_rejects_expired_profile(self):
        profile = make_profile()
        profile["ExpirationDate"] -= datetime.timedelta(days=2)
        with self.assertRaisesRegex(ValueError, "过期"):
            self.read(profile)

    def test_rejects_development_adhoc_and_enterprise_profiles(self):
        for field in ["get-task-allow", "ProvisionedDevices", "ProvisionsAllDevices"]:
            with self.subTest(field=field):
                profile = make_profile()
                if field == "get-task-allow":
                    profile["Entitlements"][field] = True
                else:
                    profile[field] = ["device"] if field == "ProvisionedDevices" else True
                with self.assertRaisesRegex(ValueError, "App Store"):
                    self.read(profile)

    def test_rejects_uuid_path_traversal(self):
        with self.assertRaisesRegex(ValueError, "UUID"):
            self.read(make_profile(uuid="../../elsewhere"))

    def test_ios_export_signs_both_targets_without_upload(self):
        main = make_profile()
        share = make_profile(APP + ".ShareExtension", uuid="AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        with patch.object(profiles.subprocess, "check_output", side_effect=[plistlib.dumps(main), plistlib.dumps(share)]):
            profiles.prepare_ios()
        with (self.signing / "ExportOptions.plist").open("rb") as source:
            export = plistlib.load(source)
        self.assertEqual(export["destination"], "export")
        self.assertEqual(export["method"], "app-store-connect")
        self.assertFalse(export["manageAppVersionAndBuildNumber"])
        self.assertEqual(export["provisioningProfiles"], {APP: main["UUID"], APP + ".ShareExtension": share["UUID"]})
        settings = (self.signing / "ios-signing.xcconfig").read_text()
        self.assertIn(f"FOLDERSPAN_PROFILE_iosApp = {main['UUID']}", settings)
        self.assertIn(f"FOLDERSPAN_PROFILE_ShareExtension = {share['UUID']}", settings)
        installed = (self.signing / "installed-profiles.txt").read_text().splitlines()
        self.assertEqual(len(installed), 4)
        self.assertTrue(all(Path(path).is_file() for path in installed))

    def test_does_not_overwrite_existing_profiles(self):
        main = make_profile()
        existing = Path.home() / "Library/MobileDevice/Provisioning Profiles" / (main["UUID"] + ".mobileprovision")
        existing.parent.mkdir(parents=True)
        existing.write_bytes(b"pre-existing")
        with patch.object(profiles.subprocess, "check_output", return_value=plistlib.dumps(main)):
            with self.assertRaises(FileExistsError):
                profiles.prepare_ios()
        self.assertEqual(existing.read_bytes(), b"pre-existing")
        self.assertFalse((self.signing / "installed-profiles.txt").exists())

    def test_mac_profiles_keep_required_jpackage_filenames(self):
        app = make_profile(platform="OSX")
        runtime = make_profile("com.oracle.java." + APP, "OSX")
        with patch.object(profiles.subprocess, "check_output", side_effect=[plistlib.dumps(app), plistlib.dumps(runtime)]):
            profiles.prepare_mac()
        self.assertTrue((self.signing / "embedded.provisionprofile").exists())
        self.assertTrue((self.signing / "runtime.provisionprofile").exists())

    def test_mac_rejects_team_different_from_entitlements(self):
        with patch.dict(os.environ, {"APPLE_TEAM_ID": "OTHERTEAM00"}):
            with self.assertRaisesRegex(ValueError, "entitlements"):
                profiles.prepare_mac()


if __name__ == "__main__":
    unittest.main()
