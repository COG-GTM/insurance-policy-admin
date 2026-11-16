import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from unittest import mock

from helpers import allowed_changes, run_quiet

import check_api


class FakeResponse:
    def __init__(self, status, body):
        self.status, self._body = status, body.encode()

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class JtypeTests(unittest.TestCase):
    def test_maps_python_types_to_json_types(self):
        self.assertEqual(
            [check_api.jtype(v) for v in (True, 1, 1.5, "s", [], {}, None)],
            ["boolean", "number", "number", "string", "array", "object", "null"],
        )


class CompareTests(unittest.TestCase):
    def compare(self, exp, act):
        out = []
        check_api.compare(exp, act, "GET /p", out)
        return out

    def test_identical_and_int_float_equal_values_pass(self):
        self.assertEqual(self.compare({"a": 1250.0, "b": [1, "x"], "c": None}, {"a": 1250, "b": [1, "x"], "c": None}), [])

    def test_changed_value_and_type(self):
        out = self.compare({"status": "AC", "premium": 10.5, "flag": True}, {"status": "CN", "premium": "10.5", "flag": 1})
        self.assertEqual(out[0], ("FAIL", "api:GET /p.status:value", "'AC' -> 'CN'"))
        self.assertEqual(out[1], ("FAIL", "api:GET /p.premium:type", 'number -> string (10.5 -> "10.5")'))
        self.assertEqual(out[2][1], "api:GET /p.flag:type")
        self.assertEqual(len(out), 3)

    def test_removed_and_added_fields(self):
        out = self.compare({"a": 1, "b": 2}, {"a": 1, "z": 3})
        self.assertEqual(
            out,
            [("FAIL", "api:GET /p.b:removed", "field missing from response"), ("NOTE", "api:GET /p.z:added", "additive field")],
        )

    def test_volatile_field_is_type_checked_only(self):
        self.assertEqual(self.compare({"lastUpdated": 1}, {"lastUpdated": 2}), [])
        out = self.compare({"lastUpdated": 1}, {"lastUpdated": "2025-01-01"})
        self.assertEqual([f[1] for f in out], ["api:GET /p.lastUpdated:type"])

    def test_arrays_keep_length_and_order(self):
        out = self.compare([{"t": "DWEL"}, {"t": "PERS"}], [{"t": "PERS"}])
        self.assertEqual(
            out, [("FAIL", "api:GET /p:length", "2 -> 1 items"), ("FAIL", "api:GET /p[0].t:value", "'DWEL' -> 'PERS'")]
        )

    def test_type_message_is_truncated(self):
        out = self.compare("x" * 100, 5)
        self.assertEqual(out[0][2], f"string -> number ({json.dumps('x' * 100)[:40]} -> 5)")


class FetchTests(unittest.TestCase):
    def test_returns_status_and_parsed_json_with_auth_header(self):
        with (
            mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "Authorization: Bearer abc"}),
            mock.patch.object(check_api.urllib.request, "urlopen", return_value=FakeResponse(200, '{"a": 1}')) as urlopen,
        ):
            self.assertEqual(check_api.fetch("http://h:1", "/api/x"), (200, {"a": 1}))
        req = urlopen.call_args[0][0]
        self.assertEqual(req.full_url, "http://h:1/api/x")
        self.assertEqual(req.get_header("Accept"), "application/json")
        self.assertEqual(req.get_header("Authorization"), "Bearer abc")
        self.assertEqual(urlopen.call_args[1], {"timeout": 15})

    def test_empty_body_and_malformed_auth_header(self):
        with (
            mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "no-colon"}),
            mock.patch.object(check_api.urllib.request, "urlopen", return_value=FakeResponse(204, "")) as urlopen,
        ):
            self.assertEqual(check_api.fetch("http://h", "/x"), (204, None))
        self.assertEqual(urlopen.call_args[0][0].header_items(), [("Accept", "application/json")])

    def test_http_error_returns_code(self):
        err = urllib.error.HTTPError("http://h/x", 404, "Not Found", {}, io.BytesIO(b""))
        with mock.patch.object(check_api.urllib.request, "urlopen", side_effect=err):
            self.assertEqual(check_api.fetch("http://h", "/x"), (404, None))


class MainTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.snap_dir = os.path.join(tmp.name, "snapshots")
        patcher = mock.patch.object(check_api, "SNAP_DIR", self.snap_dir)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write_snaps(self, snaps):
        os.makedirs(self.snap_dir)
        with open(os.path.join(self.snap_dir, "policies-v1.json"), "w") as fh:
            json.dump(snaps, fh)

    def run_main(self, argv, responses, allowed=()):
        with (
            allowed_changes(allowed),
            mock.patch.object(sys, "argv", ["check_api.py", *argv]),
            mock.patch.object(check_api, "fetch", side_effect=lambda base, p: responses[p]) as fetch,
        ):
            code, out = run_quiet(check_api.main)
        return code, out, fetch

    def test_record_writes_all_endpoints(self):
        responses = {p: (200, {"p": p}) for p in check_api.ENDPOINTS}
        code, out, fetch = self.run_main(["--record", "--base-url", "http://h:9"], responses)
        self.assertEqual(code, 0)
        self.assertIn(f"recorded {len(check_api.ENDPOINTS)} responses", out)
        self.assertEqual({c[0][0] for c in fetch.call_args_list}, {"http://h:9"})
        with open(os.path.join(self.snap_dir, "policies-v1.json")) as fh:
            data = json.load(fh)
        self.assertEqual(len(data), 8)
        self.assertEqual(data["/api/v1/policies/POL-99999999"], {"status": 200, "body": {"p": "/api/v1/policies/POL-99999999"}})

    def test_matching_responses_pass(self):
        self.write_snaps({"/a": {"status": 200, "body": {"x": 1}}, "/missing": {"status": 404, "body": None}})
        code, out, _ = self.run_main([], {"/a": (200, {"x": 1, "new": 2}), "/missing": (404, None)})
        self.assertEqual(code, 0)
        self.assertIn("== REST API contract (GET /api/v1/policies): PASS", out)
        self.assertIn("NOTE      api:GET /a.new:added", out)

    def test_status_change_fails_without_body_compare(self):
        self.write_snaps({"/a": {"status": 200, "body": {"x": 1}}})
        code, out, _ = self.run_main([], {"/a": (401, None)})
        self.assertEqual(code, 1)
        self.assertIn("FAIL      api:GET /a:status: 200 -> 401", out)
        self.assertNotIn(":type", out)

    def test_body_regression_fails_and_can_be_approved(self):
        self.write_snaps({"/a": {"status": 200, "body": {"x": 1}}})
        code, out, _ = self.run_main([], {"/a": (200, {"x": 2})})
        self.assertEqual(code, 1)
        self.assertIn("FAIL      api:GET /a.x:value: 1 -> 2", out)
        code, out, _ = self.run_main([], {"/a": (200, {"x": 2})}, [{"id": "api:GET /a.x:value", "ticket": "AB-7"}])
        self.assertEqual(code, 0)
        self.assertIn("APPROVED  api:GET /a.x:value: 1 -> 2 (ticket AB-7: )", out)

    def test_default_base_url_from_env(self):
        self.write_snaps({"/a": {"status": 200, "body": None}})
        with mock.patch.dict(os.environ, {"COMPAT_BASE_URL": "http://env:1"}):
            code, _, fetch = self.run_main([], {"/a": (200, {"anything": True})})
        self.assertEqual(code, 0)
        fetch.assert_called_once_with("http://env:1", "/a")


class EndpointTests(unittest.TestCase):
    def test_endpoints_cover_policy_and_coverages_for_each_policy(self):
        self.assertEqual(check_api.ENDPOINTS[:2], ["/api/v1/policies/POL-00000001", "/api/v1/policies/POL-00000001/coverages"])
        self.assertEqual(len(check_api.ENDPOINTS), 2 * len(check_api.POLICIES))


if __name__ == "__main__":
    unittest.main()
