import io
import json
import os
import shutil
import tempfile
import unittest
import urllib.error
from unittest import mock

import check_api
from check_api import compare, jtype

from tests import support


class FakeResponse:
    def __init__(self, status, body):
        self.status, self._body = status, body

    def read(self):
        return self._body.encode()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class JtypeTests(unittest.TestCase):
    def test_maps_python_values_to_json_types(self):
        self.assertEqual(
            [jtype(v) for v in (True, 1, 1.5, "s", [], {}, None)],
            ["boolean", "number", "number", "string", "array", "object", "null"],
        )


class CompareTests(unittest.TestCase):
    def run_compare(self, exp, act):
        out = []
        compare(exp, act, "GET /p", out)
        return out

    def test_identical_bodies_produce_no_findings(self):
        body = {
            "policyNumber": "POL-1",
            "premium": 1250.0,
            "coverages": [{"type": "DWEL"}],
            "active": True,
        }
        self.assertEqual(self.run_compare(body, json.loads(json.dumps(body))), [])

    def test_int_and_float_with_equal_value_match(self):
        self.assertEqual(self.run_compare({"premium": 1250.0}, {"premium": 1250}), [])

    def test_changed_value_fails_with_path(self):
        self.assertEqual(
            self.run_compare({"policyStatus": "AC"}, {"policyStatus": "CN"}),
            [("FAIL", "api:GET /p.policyStatus:value", "'AC' -> 'CN'")],
        )

    def test_changed_type_fails(self):
        out = self.run_compare({"riskScore": 35}, {"riskScore": "35"})
        self.assertEqual([f[1] for f in out], ["api:GET /p.riskScore:type"])
        self.assertEqual(out[0][2], 'number -> string (35 -> "35")')

    def test_removed_field_fails_and_added_field_is_note(self):
        out = self.run_compare({"a": 1, "b": 2}, {"a": 1, "c": 3})
        self.assertEqual(
            out,
            [
                ("FAIL", "api:GET /p.b:removed", "field missing from response"),
                ("NOTE", "api:GET /p.c:added", "additive field"),
            ],
        )

    def test_array_length_change_fails_and_items_are_compared_in_order(self):
        out = self.run_compare([{"t": "A"}, {"t": "B"}], [{"t": "B"}])
        self.assertEqual(
            [f[1] for f in out], ["api:GET /p:length", "api:GET /p[0].t:value"]
        )
        self.assertEqual(out[0][2], "2 -> 1 items")

    def test_volatile_field_is_type_checked_only(self):
        self.assertEqual(
            self.run_compare(
                {"lastUpdated": "2025-01-01"}, {"lastUpdated": "2026-11-23"}
            ),
            [],
        )
        out = self.run_compare({"lastUpdated": "2025-01-01"}, {"lastUpdated": None})
        self.assertEqual([f[1] for f in out], ["api:GET /p.lastUpdated:type"])

    def test_null_to_value_is_a_type_change(self):
        out = self.run_compare({"agentCode": None}, {"agentCode": "AG1"})
        self.assertEqual([f[1] for f in out], ["api:GET /p.agentCode:type"])


class FetchTests(unittest.TestCase):
    def test_returns_status_and_parsed_json_with_accept_header(self):
        with (
            mock.patch.dict(os.environ, {}, clear=False),
            mock.patch.object(
                check_api.urllib.request,
                "urlopen",
                return_value=FakeResponse(200, '{"a": 1}'),
            ) as uo,
        ):
            os.environ.pop("COMPAT_AUTH_HEADER", None)
            self.assertEqual(
                check_api.fetch("http://h", "/api/v1/policies/X"), (200, {"a": 1})
            )
        req = uo.call_args[0][0]
        self.assertEqual(req.full_url, "http://h/api/v1/policies/X")
        self.assertEqual(req.get_header("Accept"), "application/json")
        self.assertIsNone(req.get_header("Authorization"))
        self.assertEqual(uo.call_args[1], {"timeout": 15})

    def test_empty_body_is_none(self):
        with mock.patch.object(
            check_api.urllib.request, "urlopen", return_value=FakeResponse(204, "")
        ):
            self.assertEqual(check_api.fetch("http://h", "/x"), (204, None))

    def test_auth_header_is_sent_when_configured(self):
        with (
            mock.patch.dict(
                os.environ, {"COMPAT_AUTH_HEADER": "Authorization: Bearer abc:def"}
            ),
            mock.patch.object(
                check_api.urllib.request,
                "urlopen",
                return_value=FakeResponse(200, "{}"),
            ) as uo,
        ):
            check_api.fetch("http://h", "/x")
        self.assertEqual(
            uo.call_args[0][0].get_header("Authorization"), "Bearer abc:def"
        )

    def test_malformed_auth_header_is_ignored(self):
        with (
            mock.patch.dict(os.environ, {"COMPAT_AUTH_HEADER": "no-colon"}),
            mock.patch.object(
                check_api.urllib.request,
                "urlopen",
                return_value=FakeResponse(200, "{}"),
            ) as uo,
        ):
            check_api.fetch("http://h", "/x")
        self.assertEqual(
            uo.call_args[0][0].header_items(), [("Accept", "application/json")]
        )

    def test_http_error_returns_code_without_body(self):
        err = urllib.error.HTTPError(
            "http://h/x", 404, "Not Found", {}, io.BytesIO(b"")
        )
        with mock.patch.object(check_api.urllib.request, "urlopen", side_effect=err):
            self.assertEqual(check_api.fetch("http://h", "/x"), (404, None))


class MainTests(unittest.TestCase):
    def setUp(self):
        self.snap_dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.snap_dir)
        patcher = mock.patch.object(check_api, "SNAP_DIR", self.snap_dir)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write_snapshot(self, data):
        with open(os.path.join(self.snap_dir, "policies-v1.json"), "w") as fh:
            json.dump(data, fh)

    def run_main(self, argv, responses):
        cap = support.FindingsCapture()
        with (
            mock.patch.object(check_api.sys, "argv", ["check_api.py", *argv]),
            mock.patch.object(
                check_api, "fetch", lambda base, path: responses[path]
            ) as _,
            mock.patch.object(check_api, "report", cap),
            mock.patch("sys.stdout", new_callable=io.StringIO),
        ):
            rc = check_api.main()
        return rc, cap

    def test_matching_responses_pass(self):
        self.write_snapshot(
            {
                "/p/1": {"status": 200, "body": {"a": 1}},
                "/p/9": {"status": 404, "body": None},
            }
        )
        rc, cap = self.run_main(
            [], {"/p/1": (200, {"a": 1, "extra": True}), "/p/9": (404, None)}
        )
        self.assertEqual(rc, 0)
        self.assertEqual(cap.ids(), ["api:GET /p/1.extra:added"])

    def test_status_change_fails_without_comparing_body(self):
        self.write_snapshot({"/p/1": {"status": 200, "body": {"a": 1}}})
        rc, cap = self.run_main([], {"/p/1": (401, None)})
        self.assertEqual(rc, 1)
        self.assertEqual(cap.findings, [("FAIL", "api:GET /p/1:status", "200 -> 401")])

    def test_body_differences_are_reported_against_path(self):
        self.write_snapshot({"/p/1": {"status": 200, "body": {"a": 1}}})
        _rc, cap = self.run_main([], {"/p/1": (200, {"a": 2})})
        self.assertEqual(cap.ids(), ["api:GET /p/1.a:value"])

    def test_record_writes_every_endpoint_sorted(self):
        responses = {p: (200, {"path": p}) for p in check_api.ENDPOINTS}
        rc, _ = self.run_main(["--record", "--base-url", "http://x"], responses)
        self.assertEqual(rc, 0)
        with open(os.path.join(self.snap_dir, "policies-v1.json")) as fh:
            text = fh.read()
        data = json.loads(text)
        self.assertEqual(set(data), set(check_api.ENDPOINTS))
        self.assertEqual(
            data["/api/v1/policies/POL-00000001"],
            {"status": 200, "body": {"path": "/api/v1/policies/POL-00000001"}},
        )
        self.assertTrue(text.endswith("}\n"))

    def test_base_url_defaults_from_environment(self):
        self.write_snapshot({"/p": {"status": 200, "body": None}})
        seen = []
        with (
            mock.patch.dict(os.environ, {"COMPAT_BASE_URL": "http://facade:18080"}),
            mock.patch.object(check_api.sys, "argv", ["check_api.py"]),
            mock.patch.object(
                check_api,
                "fetch",
                lambda base, path: seen.append(base) or (200, {"x": 1}),
            ),
            mock.patch.object(check_api, "report", support.FindingsCapture()),
        ):
            self.assertEqual(check_api.main(), 0)
        self.assertEqual(seen, ["http://facade:18080"])

    def test_endpoints_cover_policy_and_coverages_for_each_policy(self):
        self.assertEqual(len(check_api.ENDPOINTS), 2 * len(check_api.POLICIES))
        self.assertIn("/api/v1/policies/POL-99999999/coverages", check_api.ENDPOINTS)


if __name__ == "__main__":
    unittest.main()
