import unittest
import urllib.error
import urllib.request
from threading import Thread

from verify_webrtc_android_pair import Bridge


class BridgeTest(unittest.TestCase):
    def test_signals_remain_separate_and_evidence_is_shared(self):
        bridge = Bridge()
        thread = Thread(target=bridge.serve_forever, daemon=True)
        thread.start()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def request(role, route, data=None):
            with opener.open(f"http://127.0.0.1:{bridge.server_address[1]}/{role}/{route}", data=data, timeout=2) as response:
                return response.status, response.read()

        try:
            self.assertEqual((200, b"ok"), request("server", "incoming", b"offer"))
            self.assertEqual((204, b""), request("server", "outgoing"))
            self.assertEqual((200, b"offer"), request("client", "outgoing"))
            self.assertEqual((204, b""), request("client", "outgoing"))
            request("client", "incoming", b"answer")
            self.assertEqual((200, b"answer"), request("server", "outgoing"))
            request("server", "bootstrap", b"authorization")
            self.assertEqual((200, b"authorization"), request("client", "bootstrap"))
            request("client", "evidence", b"classified-path")
            self.assertEqual((200, b"classified-path"), request("server", "evidence"))
            for _ in range(128):
                request("server", "incoming", b"candidate")
            with self.assertRaises(urllib.error.HTTPError) as error:
                request("server", "incoming", b"overflow")
            self.assertEqual(400, error.exception.code)
            error.exception.close()
            request("client", "finished", b"")
            request("server", "finished", b"")
            self.assertEqual({"client", "server"}, bridge.finished)
        finally:
            bridge.shutdown()
            thread.join(timeout=5)
            bridge.server_close()
        self.assertFalse(thread.is_alive())


if __name__ == "__main__":
    unittest.main()
