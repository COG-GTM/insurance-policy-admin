import io
import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from contextlib import redirect_stdout
from unittest import mock

import support  # noqa: F401
import check_api
from check_api import compare, jtype


class FakeApi:
    """Local HTTP server serving {path: (status, body)}; records request headers."""

    def __init__(self, routes):
        self.routes, self.headers = routes, []
        api = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                api.headers.append(dict(self.headers))
                status, body = api.routes.get(self.path, (404, ""))
                data = body if isinstance(body, str) else json.dumps(body)
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(data.encode())

            def log_message(self, *args):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class JtypeTest(unittest.TestCase):
    def test_json_types(self):
        self.assertEqual([jtype(v) for v in (True, 1, 1.5, "s", [], {}, None)],
                         ["boolean", "number", "number", "string", "array", "object", "null"])


class CompareTest(unittest.TestCase):
    def run_compare(self, exp, act):
        out = []
        compare(exp, act, "GET /p", out)
        return out

    def test_identical_bodies(self):
        body = {"a": 1, "b": [1, {"c": "x"}], "d": None}
        self.assertEqual(self.run_compare(body, json.loads(json.dumps(body))), [])

    def test_int_and_float_are_same_number(self):
        self.assertEqual(self.run_compare({"p": 1250.0}, {"p": 1250}), [])

    def test_type_change(self):
        out = self.run_compare({"p": 1250.0}, {"p": "1250.00"})
        self.assertEqual([(s, i) for s, i, _ in out], [("FAIL", "api:GET /p.p:type")])
        self.assertIn("number -> string", out[0][2])

    def test_removed_and_added_fields(self):
        out = self.run_compare({"a": 1, "b": 2}, {"a": 1, "c": 3})
        self.assertEqual(sorted((s, i) for s, i, _ in out), [("FAIL", "api:GET /p.b:removed"), ("NOTE", "api:GET /p.c:added")])

    def test_value_change(self):
        out = self.run_compare({"status": "AC"}, {"status": "CN"})
        self.assertEqual(out, [("FAIL", "api:GET /p.status:value", "'AC' -> 'CN'")])

    def test_volatile_field_is_type_checked_only(self):
        self.assertEqual(self.run_compare({"lastUpdated": "2025-01-01"}, {"lastUpdated": "2026-02-02"}), [])
        self.assertEqual([i for _, i, _ in self.run_compare({"lastUpdated": "2025"}, {"lastUpdated": None})],
                         ["api:GET /p.lastUpdated:type"])

    def test_array_length_and_element_paths(self):
        out = self.run_compare([{"t": "DWEL"}, {"t": "PERS"}], [{"t": "DWEL"}, {"t": "LIAB"}, {"t": "X"}])
        self.assertEqual([(s, i) for s, i, _ in out], [("FAIL", "api:GET /p:length"), ("FAIL", "api:GET /p[1].t:value")])
        self.assertIn("2 -> 3 items", out[0][2])

    def test_null_body_replacing_object(self):
        self.assertEqual([i for _, i, _ in self.run_compare({"a": 1}, None)], ["api:GET /p:type"])


class FetchTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeApi({"/ok": (200, {"x": 1}), "/empty": (200, ""), "/err": (500, {"e": 1})})
        self.addCleanup(self.api.close)

    def test_ok_json(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("COMPAT_AUTH_HEADER", None)
            self.assertEqual(check_api.fetch(self.api.url, "/ok"), (200, {"x": 1}))
        self.assertEqual(self.api.headers[-1]["Accept"], "application/json")
        self.assertNotIn("Authorization", self.api.headers[-1])

    def test_empty_body_is_none(self):
        self.assertEqual(check_api.fetch(self.api.url, "/empty"), (200, None))

    def test_http_errors_return_status_without_body(self):
        self.assertEqual(check_api.fetch(self.api.url, "/missing"), (404, None))
        self.assertEqual(check_api.fetch(self.api.url, "/err"), (500, None))

    def test_auth_header_from_environment(self):
        with mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "Authorization: Bearer a:b"}):
            check_api.fetch(self.api.url, "/ok")
        self.assertEqual(self.api.headers[-1]["Authorization"], "Bearer a:b")

    def test_malformed_auth_header_is_ignored(self):
        with mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "no-colon"}):
            self.assertEqual(check_api.fetch(self.api.url, "/ok")[0], 200)
        self.assertNotIn("no-colon", self.api.headers[-1])


class MainTest(unittest.TestCase):
    def setUp(self):
        self.snap_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.snap_dir.cleanup)

    def run_main(self, api, *args):
        with mock.patch.object(check_api, "SNAP_DIR", os.path.join(self.snap_dir.name, "snaps")), \
                mock.patch.object(check_api, "report", side_effect=lambda name, f: f), \
                mock.patch("sys.argv", ["check_api.py", "--base-url", api.url, *args]), \
                redirect_stdout(io.StringIO()):
            return check_api.main()

    def write_snapshot(self, data):
        os.makedirs(os.path.join(self.snap_dir.name, "snaps"))
        with open(os.path.join(self.snap_dir.name, "snaps", "policies-v1.json"), "w") as fh:
            json.dump(data, fh)

    def test_record_writes_every_endpoint(self):
        p1 = "/api/v1/policies/POL-00000001"
        api = FakeApi({p1: (200, {"policyNumber": "POL-00000001"})})
        self.addCleanup(api.close)
        self.assertEqual(self.run_main(api, "--record"), 0)
        with open(os.path.join(self.snap_dir.name, "snaps", "policies-v1.json")) as fh:
            snap = json.load(fh)
        self.assertEqual(set(snap), set(check_api.ENDPOINTS))
        self.assertEqual(snap[p1], {"status": 200, "body": {"policyNumber": "POL-00000001"}})
        self.assertEqual(snap["/api/v1/policies/POL-99999999"], {"status": 404, "body": None})

    def test_compare_against_snapshot(self):
        self.write_snapshot({
            "/same": {"status": 200, "body": {"a": 1}},
            "/changed": {"status": 200, "body": {"a": 1}},
            "/gone": {"status": 200, "body": {"a": 1}},
            "/missing": {"status": 404, "body": None},
        })
        api = FakeApi({"/same": (200, {"a": 1, "new": True}), "/changed": (200, {"a": 2})})
        self.addCleanup(api.close)
        findings = self.run_main(api)
        self.assertEqual(sorted((s, i) for s, i, _ in findings), [
            ("FAIL", "api:GET /changed.a:value"),
            ("FAIL", "api:GET /gone:status"),
            ("NOTE", "api:GET /same.new:added"),
        ])

    def test_repo_snapshot_covers_all_endpoints(self):
        with open(os.path.join(support.COMPAT, "api", "snapshots", "policies-v1.json")) as fh:
            snap = json.load(fh)
        self.assertEqual(set(snap), set(check_api.ENDPOINTS))
        self.assertEqual(snap["/api/v1/policies/POL-99999999"]["status"], 404)


if __name__ == "__main__":
    unittest.main()
