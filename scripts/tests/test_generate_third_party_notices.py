import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "generate_third_party_notices.py"
spec = importlib.util.spec_from_file_location("notices", SCRIPT)
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class NoticeGenerationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "third-party/maven").mkdir(parents=True)
        (self.root / "third-party/maven/components.csv").write_text(
            "component,configurations,license,license_texts\n"
            "example:library:1.0,Android,MIT,third-party/LICENSE.txt\n"
        )

    def write_manifest(self, payload):
        path = self.root / "third-party/LICENSE.txt"
        path.write_bytes(payload)
        (self.root / "third-party/manifest.json").write_text(json.dumps({
            "scope": "fixture scope",
            "texts": [{"file": "third-party/LICENSE.txt", "name": "Example",
                       "source_url": "https://example.org/source/LICENSE",
                       "sha256": hashlib.sha256(payload).hexdigest()}],
        }))

    def test_preserves_author_notice_and_full_license_in_output(self):
        payload = b"Copyright Example Author\nPermission is hereby granted.\n"
        self.write_manifest(payload)
        output = notices.render_notices(self.root)
        self.assertIn(payload, output)
        self.assertIn(b"example:library:1.0", output)

    def test_rejects_binary_mistaken_for_license_text(self):
        self.write_manifest(b"\xca\xfe\xba\xbe\x00\x00LICENSE.class")
        with self.assertRaisesRegex(ValueError, "binary"):
            notices.render_notices(self.root)

    def test_rejects_modified_source_text_without_new_digest(self):
        self.write_manifest(b"Original copyright and license.\n")
        (self.root / "third-party/LICENSE.txt").write_bytes(b"Changed text.\n")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            notices.render_notices(self.root)

    def test_rejects_component_without_referenced_license(self):
        self.write_manifest(b"Original copyright and license.\n")
        (self.root / "third-party/maven/components.csv").write_text(
            "component,configurations,license,license_texts\n"
            "example:library:1.0,Android,MIT,missing.txt\n"
        )
        with self.assertRaisesRegex(ValueError, "license text"):
            notices.render_notices(self.root)


if __name__ == "__main__":
    unittest.main()
