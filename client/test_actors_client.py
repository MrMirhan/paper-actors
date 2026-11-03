import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from actors_client import ActorsClient, ActorsError, canonical, sign  # noqa: E402

SECRET = "0123456789abcdef-secret"
# The same vector is asserted in SignerTest.java; if one side changes, both fail.
GOLDEN = "5c92a983d94b7677edaf95049b67e8c7f27d92c352ce282ff7fca9451ac122e5"


class Signing(unittest.TestCase):
    def test_golden_signature_matches_the_java_side(self):
        self.assertEqual(sign(SECRET, "POST", "/v1/act", 1700000000000, "n0nce0123456789ab", b'{"name":"Rambox"}'), GOLDEN)

    def test_canonical_form(self):
        c = canonical("get", "/v1/state", 5, "abc", b"")
        self.assertEqual(c, "GET\n/v1/state\n5\nabc\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

    def test_short_secret_is_refused(self):
        with self.assertRaises(ValueError):
            ActorsClient("127.0.0.1", 1, "short")


class Roundtrip(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seen = cls.seen = []

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _serve(self):
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n)
                seen.append((self.command, self.path, dict(self.headers), body))
                code = 404 if self.path == "/v1/missing" else 200
                out = json.dumps({"ok": code == 200} if code == 200 else {"ok": False, "error": "no such route"}).encode()
                self.send_response(code)
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            do_GET = do_POST = _serve

        cls.http = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=cls.http.serve_forever, daemon=True).start()
        cls.client = ActorsClient("127.0.0.1", cls.http.server_address[1], SECRET)

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()

    def test_request_is_signed_over_what_was_sent(self):
        self.client.spawn("Rambox", at=[0, 64, 0], profile="tryhard")
        method, path, headers, body = self.seen[-1]
        self.assertEqual((method, path), ("POST", "/v1/spawn"))
        want = sign(SECRET, method, path, headers["X-Actors-Time"], headers["X-Actors-Nonce"], body)
        self.assertEqual(headers["X-Actors-Signature"], want)
        self.assertEqual(json.loads(body), {"name": "Rambox", "at": [0, 64, 0], "profile": "tryhard"})

    def test_nonce_is_fresh_each_time(self):
        self.client.state()
        self.client.state()
        self.assertNotEqual(self.seen[-1][2]["X-Actors-Nonce"], self.seen[-2][2]["X-Actors-Nonce"])

    def test_query_is_part_of_the_signature(self):
        self.client.events(since=7, limit=5)
        method, path, headers, body = self.seen[-1]
        self.assertEqual(path, "/v1/events?since=7&limit=5")
        self.assertEqual(headers["X-Actors-Signature"], sign(SECRET, method, path, headers["X-Actors-Time"], headers["X-Actors-Nonce"], body))

    def test_error_status_raises(self):
        with self.assertRaises(ActorsError) as ctx:
            self.client.request("GET", "/v1/missing")
        self.assertEqual(ctx.exception.status, 404)


if __name__ == "__main__":
    unittest.main()
