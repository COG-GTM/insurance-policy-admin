import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stdout
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "api"))
import check_api as api  # noqa: E402


class FakeResponse:
    def __init__(self, status, body):
        self.status, self._body = status, body

    def read(self):
        return self._body.encode()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def compare(exp, act):
    out = []
    api.compare(exp, act, "GET /p", out)
    return {fid: sev for sev, fid, _ in out}


class JtypeTests(unittest.TestCase):
    def test_json_type_names(self):
        self.assertEqual([api.jtype(v) for v in (True, 1, 1.5, "s", [], {}, None)],
                         ["boolean", "number", "number", "string", "array", "object", "null"])


class CompareTests(unittest.TestCase):
    def test_identical_and_int_float_equal(self):
        self.assertEqual(compare({"a": 1250.0, "b": [1, "x"]}, {"a": 1250, "b": [1, "x"]}), {})

    def test_type_change(self):
        self.assertEqual(compare({"a": 1}, {"a": "1"}), {"api:GET /p.a:type": "FAIL"})
        self.assertEqual(compare({"a": None}, {"a": 0}), {"api:GET /p.a:type": "FAIL"})

    def test_removed_and_added_fields(self):
        self.assertEqual(compare({"a": 1, "b": 2}, {"a": 1, "c": 3}),
                         {"api:GET /p.b:removed": "FAIL", "api:GET /p.c:added": "NOTE"})

    def test_value_change(self):
        out = []
        api.compare({"status": "AC"}, {"status": "CN"}, "GET /p", out)
        self.assertEqual(out, [("FAIL", "api:GET /p.status:value", "'AC' -> 'CN'")])

    def test_volatile_field_type_checked_only(self):
        self.assertEqual(compare({"lastUpdated": 1}, {"lastUpdated": 2}), {})
        self.assertEqual(compare({"lastUpdated": 1}, {"lastUpdated": "x"}), {"api:GET /p.lastUpdated:type": "FAIL"})

    def test_array_length_and_nested_paths(self):
        exp = [{"type": "DWEL"}, {"type": "PERS"}]
        self.assertEqual(compare(exp, [{"type": "DWEL"}]), {"api:GET /p:length": "FAIL"})
        self.assertEqual(compare(exp, [{"type": "PERS"}, {"type": "DWEL"}]),
                         {"api:GET /p[0].type:value": "FAIL", "api:GET /p[1].type:value": "FAIL"})

    def test_whole_body_type_change(self):
        self.assertEqual(compare([], {}), {"api:GET /p:type": "FAIL"})


class FetchTests(unittest.TestCase):
    def test_parses_json_and_sends_accept(self):
        with mock.patch.object(api.urllib.request, "urlopen", return_value=FakeResponse(200, '{"a": 1}')) as uo, \
                mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("COMPAT_AUTH_HEADER", None)
            self.assertEqual(api.fetch("http://h", "/x"), (200, {"a": 1}))
        req = uo.call_args[0][0]
        self.assertEqual(req.full_url, "http://h/x")
        self.assertEqual(req.get_header("Accept"), "application/json")
        self.assertIsNone(req.get_header("Authorization"))
        self.assertEqual(uo.call_args[1], {"timeout": 15})

    def test_empty_body_is_none(self):
        with mock.patch.object(api.urllib.request, "urlopen", return_value=FakeResponse(204, "")):
            self.assertEqual(api.fetch("http://h", "/x"), (204, None))

    def test_auth_header_added(self):
        with mock.patch.object(api.urllib.request, "urlopen", return_value=FakeResponse(200, "[]")) as uo, \
                mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "Authorization: Bearer a:b"}):
            api.fetch("http://h", "/x")
        self.assertEqual(uo.call_args[0][0].get_header("Authorization"), "Bearer a:b")

    def test_malformed_auth_header_ignored(self):
        with mock.patch.object(api.urllib.request, "urlopen", return_value=FakeResponse(200, "[]")) as uo, \
                mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "no-colon"}):
            api.fetch("http://h", "/x")
        self.assertEqual(uo.call_args[0][0].header_items(), [("Accept", "application/json")])

    def test_http_error_returns_status(self):
        err = urllib.error.HTTPError("http://h/x", 404, "Not Found", {}, None)
        with mock.patch.object(api.urllib.request, "urlopen", side_effect=err):
            self.assertEqual(api.fetch("http://h", "/x"), (404, None))


class MainTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.snap_dir = os.path.join(self.tmp.name, "snaps")
        self.snap_file = os.path.join(self.snap_dir, "policies-v1.json")
        self.patch = mock.patch.object(api, "SNAP_DIR", self.snap_dir)
        self.patch.start()

    def tearDown(self):
        self.patch.stop()
        self.tmp.cleanup()

    def write_snaps(self, snaps):
        os.makedirs(self.snap_dir)
        with open(self.snap_file, "w") as fh:
            json.dump(snaps, fh)

    def run_main(self, argv, responses):
        captured = {}

        def fake_report(name, findings):
            captured["findings"] = findings
            return 1 if any(s == "FAIL" for s, _, _ in findings) else 0
        calls = []

        def fake_fetch(base, path):
            calls.append(base)
            return responses[path]
        with mock.patch.object(sys, "argv", ["check_api.py", *argv]), \
                mock.patch.object(api, "fetch", fake_fetch), mock.patch.object(api, "report", fake_report), \
                redirect_stdout(io.StringIO()) as out:
            code = api.main()
        return code, captured.get("findings"), calls, out.getvalue()

    def test_record_writes_every_endpoint(self):
        responses = {p: (200, {"p": p}) for p in api.ENDPOINTS}
        code, _, calls, out = self.run_main(["--record", "--base-url", "http://x:1"], responses)
        with open(self.snap_file) as fh:
            data = json.load(fh)
        self.assertEqual(code, 0)
        self.assertEqual(set(data), set(api.ENDPOINTS))
        self.assertEqual(data["/api/v1/policies/POL-00000001"], {"status": 200, "body": {"p": "/api/v1/policies/POL-00000001"}})
        self.assertEqual(set(calls), {"http://x:1"})
        self.assertIn(f"recorded {len(api.ENDPOINTS)} responses", out)

    def test_matching_responses_pass(self):
        self.write_snaps({"/a": {"status": 200, "body": {"x": 1}}, "/b": {"status": 404, "body": None}})
        code, findings, calls, _ = self.run_main([], {"/a": (200, {"x": 1, "y": 2}), "/b": (404, None)})
        self.assertEqual(code, 0)
        self.assertEqual(findings, [("NOTE", "api:GET /a.y:added", "additive field")])

    def test_status_change_and_body_diff_fail(self):
        self.write_snaps({"/a": {"status": 200, "body": {"x": 1}}, "/b": {"status": 404, "body": None}})
        code, findings, _, _ = self.run_main([], {"/a": (200, {"x": 2}), "/b": (401, None)})
        self.assertEqual(code, 1)
        self.assertEqual({f[1] for f in findings}, {"api:GET /a.x:value", "api:GET /b:status"})
        self.assertIn(("FAIL", "api:GET /b:status", "404 -> 401"), findings)

    def test_base_url_from_environment(self):
        self.write_snaps({"/a": {"status": 404, "body": None}})
        with mock.patch.dict(os.environ, {"COMPAT_BASE_URL": "http://env:9"}):
            _, _, calls, _ = self.run_main([], {"/a": (404, None)})
        self.assertEqual(calls, ["http://env:9"])

    def test_repo_snapshots_cover_all_endpoints(self):
        with open(os.path.join(os.path.dirname(api.__file__), "snapshots", "policies-v1.json")) as fh:
            snaps = json.load(fh)
        self.assertEqual(set(snaps), set(api.ENDPOINTS))
        self.assertEqual(snaps["/api/v1/policies/POL-99999999"]["status"], 404)


if __name__ == "__main__":
    unittest.main()
