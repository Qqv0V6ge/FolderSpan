import contextlib
import io
import unittest

from verify_webrtc_interop import options, evidence_lines


class OptionsTest(unittest.TestCase):
    def test_evidence_keeps_source_and_unknown_fields(self):
        report = 'log\nWEBRTC_ICE_EVIDENCE {"observation":{"source":"Remote","path":{"remoteFamily":"Unknown"}},"sha256":"hash"}\n'
        self.assertEqual("Remote", evidence_lines(report)[0]["observation"]["source"])
        self.assertEqual("Unknown", evidence_lines(report)[0]["observation"]["path"]["remoteFamily"])

    def test_local_smoke_and_remote_ipv6_endpoint(self):
        self.assertEqual("local", options([]).role)
        args = options(["--role", "browser", "--bridge-url", "http://[2001:db8::1]:18390",
                        "--network-scenario", "ipv6-direct", "--expected-path", "Direct",
                        "--expected-family", "IPv6", "--browser-target", "wasmJs"])
        self.assertEqual("IPv6", args.expected_family)
        self.assertEqual("wasmJs", args.browser_target)
        self.assertEqual("Udp", options(["--expected-protocol", "Udp"]).expected_protocol)
        self.assertTrue(options(["--keep-existing-lockfiles"]).keep_existing_lockfiles)

    def test_native_client_reuses_remote_signaling_and_path_assertions(self):
        args = options(["--role", "jvm-client", "--bridge-url", "http://198.51.100.1:18390",
                        "--network-scenario", "ipv4-nat", "--expected-path", "Direct",
                        "--expected-family", "IPv4", "--expected-protocol", "Udp"])
        self.assertEqual("jvm-client", args.role)
        self.assertEqual("IPv4", args.expected_family)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            options(["--role", "jvm-client"])

    def test_missing_network_requirements_and_embedded_secrets_fail(self):
        for arguments in (["--role", "browser"], ["--port", "0"],
                          ["--network-scenario", "ipv4-nat", "--expected-path", "Direct"],
                          ["--role", "browser", "--bridge-url", "http://127.0.0.1:18390",
                           "--network-scenario", "ipv4-nat", "--expected-path", "Direct"],
                          ["--bridge-url", "http://user:secret@example.com"]):
            with self.subTest(arguments=arguments), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    options(arguments)


if __name__ == "__main__":
    unittest.main()
