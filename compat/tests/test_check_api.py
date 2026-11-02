import io
import json
import os
import tempfile
import unittest
import urllib.error
from unittest import mock

from support import captured_findings, ids

import check_api


def run_compare(exp, act):
    out = []
    check_api.compare(exp, act, "GET /x", out)
    return out


class JtypeTests(unittest.TestCase):
    def test_maps_python_values_to_json_types(self):
        self.assertEqual(
            [check_api.jtype(v) for v in (True, 1, 1.5, "s", [], {}, None)],
            ["boolean", "number", "number", "string", "array", "object", "null"],
        )


class CompareTests(unittest.TestCase):
    def test_identical_bodies_produce_no_findings(self):
        body = {"policyNumber": "POL-1", "coverages": [{"seq": 1}], "premium": 12.5}
        self.assertEqual(run_compare(body, json.loads(json.dumps(body))), [])

    def test_int_and_float_are_the_same_json_type(self):
        self.assertEqual(run_compare({"p": 1250}, {"p": 1250.0}), [])

    def test_removed_field_fails(self):
        out = run_compare({"a": 1, "b": 2}, {"a": 1})
        self.assertEqual(out, [("FAIL", "api:GET /x.b:removed", "field missing from response")])

    def test_added_field_is_a_note(self):
        out = run_compare({"a": 1}, {"a": 1, "z": 0})
        self.assertEqual(out, [("NOTE", "api:GET /x.z:added", "additive field")])

    def test_type_change_fails_with_both_values(self):
        out = run_compare({"premium": 1250.0}, {"premium": "1250.00"})
        self.assertEqual(ids(out), {"api:GET /x.premium:type"})
        self.assertEqual(out[0][2], 'number -> string (1250.0 -> "1250.00")')

    def test_null_to_value_is_a_type_change(self):
        self.assertEqual(ids(run_compare({"agent": None}, {"agent": "AG1"})), {"api:GET /x.agent:type"})

    def test_value_change_fails(self):
        out = run_compare({"status": "AC"}, {"status": "CN"})
        self.assertEqual(out, [("FAIL", "api:GET /x.status:value", "'AC' -> 'CN'")])

    def test_volatile_field_ignores_value_but_not_type(self):
        self.assertEqual(run_compare({"lastUpdated": 1}, {"lastUpdated": 2}), [])
        self.assertEqual(ids(run_compare({"lastUpdated": 1}, {"lastUpdated": "x"})), {"api:GET /x.lastUpdated:type"})

    def test_array_length_change_and_element_paths(self):
        out = run_compare([{"t": "DWEL"}, {"t": "PERS"}], [{"t": "DWEL"}, {"t": "OTHR"}, {"t": "NEW"}])
        self.assertEqual(ids(out), {"api:GET /x:length", "api:GET /x[1].t:value"})
        self.assertIn(("FAIL", "api:GET /x:length", "2 -> 3 items"), out)

    def test_volatile_key_only_applies_to_direct_field(self):
        out = run_compare({"lastUpdated": [1]}, {"lastUpdated": [2]})
        self.assertEqual(ids(out), {"api:GET /x.lastUpdated[0]:value"})


class FakeResponse(io.BytesIO):
    def __init__(self, status, body):
        super().__init__(body.encode())
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FetchTests(unittest.TestCase):
    def test_returns_status_and_parsed_json(self):
        with mock.patch.object(check_api.urllib.request, "urlopen", return_value=FakeResponse(200, '{"a": 1}')) as urlopen, \
                mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("COMPAT_AUTH_HEADER", None)
            self.assertEqual(check_api.fetch("http://h", "/p"), (200, {"a": 1}))
        req = urlopen.call_args[0][0]
        self.assertEqual(req.full_url, "http://h/p")
        self.assertEqual(req.get_header("Accept"), "application/json")
        self.assertIsNone(req.get_header("Authorization"))
        self.assertEqual(urlopen.call_args[1], {"timeout": 15})

    def test_empty_body_is_none(self):
        with mock.patch.object(check_api.urllib.request, "urlopen", return_value=FakeResponse(204, "")):
            self.assertEqual(check_api.fetch("http://h", "/p"), (204, None))

    def test_http_error_returns_code_without_body(self):
        err = urllib.error.HTTPError("http://h/p", 404, "Not Found", {}, None)
        with mock.patch.object(check_api.urllib.request, "urlopen", side_effect=err):
            self.assertEqual(check_api.fetch("http://h", "/p"), (404, None))

    def test_auth_header_from_environment(self):
        with mock.patch.object(check_api.urllib.request, "urlopen", return_value=FakeResponse(200, "{}")) as urlopen, \
                mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "Authorization: Bearer a:b"}):
            check_api.fetch("http://h", "/p")
        self.assertEqual(urlopen.call_args[0][0].get_header("Authorization"), "Bearer a:b")

    def test_malformed_auth_header_is_ignored(self):
        with mock.patch.object(check_api.urllib.request, "urlopen", return_value=FakeResponse(200, "{}")) as urlopen, \
                mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "no-colon-here"}):
            check_api.fetch("http://h", "/p")
        self.assertEqual(len(urlopen.call_args[0][0].header_items()), 1)


class MainTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        patcher = mock.patch.object(check_api, "SNAP_DIR", os.path.join(self.tmp.name, "snaps"))
        patcher.start()
        self.addCleanup(patcher.stop)

    def write_snapshots(self, snaps):
        os.makedirs(check_api.SNAP_DIR)
        with open(os.path.join(check_api.SNAP_DIR, "policies-v1.json"), "w") as fh:
            json.dump(snaps, fh)

    def run_main(self, argv, responses):
        with mock.patch.object(check_api.sys, "argv", ["check_api.py", *argv]), \
                mock.patch.object(check_api, "fetch", side_effect=lambda base, path: responses[path]) as fetch, \
                captured_findings(check_api) as findings:
            code = check_api.main()
        return code, findings, fetch

    def test_matching_responses_pass_and_use_base_url(self):
        self.write_snapshots({"/a": {"status": 200, "body": {"x": 1}}, "/b": {"status": 404, "body": None}})
        code, findings, fetch = self.run_main(["--base-url", "http://svc:1"], {"/a": (200, {"x": 1}), "/b": (404, None)})
        self.assertEqual((code, findings), (0, []))
        fetch.assert_any_call("http://svc:1", "/a")

    def test_status_change_fails_without_comparing_body(self):
        self.write_snapshots({"/a": {"status": 200, "body": {"x": 1}}})
        code, findings, _ = self.run_main([], {"/a": (401, None)})
        self.assertEqual(code, 1)
        self.assertEqual(findings, [("FAIL", "api:GET /a:status", "200 -> 401")])

    def test_body_differences_are_reported(self):
        self.write_snapshots({"/a": {"status": 200, "body": {"x": 1, "y": "k"}}})
        code, findings, _ = self.run_main([], {"/a": (200, {"x": 2})})
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {"api:GET /a.x:value", "api:GET /a.y:removed"})

    def test_record_mode_writes_every_endpoint(self):
        responses = {p: (200, {"p": p}) for p in check_api.ENDPOINTS}
        with mock.patch("builtins.print"):
            code, _, _ = self.run_main(["--record"], responses)
        self.assertEqual(code, 0)
        with open(os.path.join(check_api.SNAP_DIR, "policies-v1.json")) as fh:
            data = json.load(fh)
        self.assertEqual(set(data), set(check_api.ENDPOINTS))
        self.assertEqual(data["/api/v1/policies/POL-00000001"], {"status": 200, "body": {"p": "/api/v1/policies/POL-00000001"}})
        self.assertEqual(len(check_api.ENDPOINTS), 2 * len(check_api.POLICIES))


class RecordedSnapshotTests(unittest.TestCase):
    def test_repository_snapshots_cover_all_endpoints(self):
        with open(os.path.join(check_api.SNAP_DIR, "policies-v1.json")) as fh:
            snaps = json.load(fh)
        self.assertEqual(set(snaps), set(check_api.ENDPOINTS))
        self.assertEqual(snaps["/api/v1/policies/POL-99999999"]["status"], 404)


if __name__ == "__main__":
    unittest.main()
