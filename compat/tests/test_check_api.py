import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import check_api
import pytest


@pytest.fixture
def server():
    """Local HTTP server; tests set `server.routes[path] = (status, body)`."""
    routes, seen_headers = {}, []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            seen_headers.append(dict(self.headers))
            status, body = routes.get(self.path, (404, None))
            payload = b"" if body is None else json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args):
            pass

    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    httpd.routes, httpd.seen_headers = routes, seen_headers
    httpd.url = f"http://127.0.0.1:{httpd.server_address[1]}"
    yield httpd
    httpd.shutdown()
    httpd.server_close()


def compare(exp, act):
    out = []
    check_api.compare(exp, act, "GET /p", out)
    return out


# --- jtype / compare ---------------------------------------------------------

def test_jtype_maps_python_values_to_json_types():
    assert [check_api.jtype(v) for v in (True, 1, 1.5, "s", [], {}, None)] == [
        "boolean", "number", "number", "string", "array", "object", "null"]


def test_compare_identical_bodies_have_no_findings():
    body = {"policyNumber": "POL-1", "totalPremium": 1250.0, "coverages": [{"seq": 1}]}
    assert compare(body, json.loads(json.dumps(body))) == []


def test_compare_int_and_float_are_both_number():
    assert compare({"a": 1}, {"a": 1.0}) == []


def test_compare_type_change_fails():
    out = compare({"totalPremium": 1250.0}, {"totalPremium": "1250.00"})
    assert out == [("FAIL", "api:GET /p.totalPremium:type", 'number -> string (1250.0 -> "1250.00")')]


def test_compare_bool_vs_number_is_type_change():
    assert compare({"f": True}, {"f": 1})[0][1] == "api:GET /p.f:type"


def test_compare_removed_field_fails_and_added_field_is_note():
    out = compare({"a": 1, "b": 2}, {"a": 1, "c": 3})
    assert ("FAIL", "api:GET /p.b:removed", "field missing from response") in out
    assert ("NOTE", "api:GET /p.c:added", "additive field") in out


def test_compare_value_change_fails():
    assert compare({"status": "AC"}, {"status": "CN"}) == [("FAIL", "api:GET /p.status:value", "'AC' -> 'CN'")]


def test_compare_volatile_field_checks_type_only():
    assert compare({"lastUpdated": "2025-01-01"}, {"lastUpdated": "2026-10-26"}) == []
    assert compare({"lastUpdated": "2025-01-01"}, {"lastUpdated": None})[0][1] == "api:GET /p.lastUpdated:type"


def test_compare_array_length_and_element_paths():
    out = compare([{"t": "DWEL"}, {"t": "PERS"}], [{"t": "DWEL"}, {"t": "LIAB"}, {"t": "X"}])
    assert ("FAIL", "api:GET /p:length", "2 -> 3 items") in out
    assert ("FAIL", "api:GET /p[1].t:value", "'PERS' -> 'LIAB'") in out
    assert len(out) == 2


def test_compare_nested_object_paths():
    out = compare({"a": {"b": {"c": 1}}}, {"a": {"b": {}}})
    assert out == [("FAIL", "api:GET /p.a.b.c:removed", "field missing from response")]


# --- fetch -------------------------------------------------------------------

def test_fetch_returns_status_and_json(server):
    server.routes["/x"] = (200, {"a": 1})
    assert check_api.fetch(server.url, "/x") == (200, {"a": 1})
    assert server.seen_headers[-1]["Accept"] == "application/json"


def test_fetch_http_error_returns_status_and_none(server):
    assert check_api.fetch(server.url, "/missing") == (404, None)


def test_fetch_empty_body_is_none(server):
    server.routes["/empty"] = (200, None)
    assert check_api.fetch(server.url, "/empty") == (200, None)


def test_fetch_sends_auth_header_from_env(server, monkeypatch):
    server.routes["/x"] = (200, {})
    monkeypatch.setenv("COMPAT_AUTH_HEADER", "Authorization: Bearer abc:def")
    check_api.fetch(server.url, "/x")
    assert server.seen_headers[-1]["Authorization"] == "Bearer abc:def"


def test_fetch_ignores_malformed_auth_header(server, monkeypatch):
    server.routes["/x"] = (200, {})
    monkeypatch.setenv("COMPAT_AUTH_HEADER", "no-colon-here")
    check_api.fetch(server.url, "/x")
    assert "no-colon-here" not in json.dumps(server.seen_headers[-1])


# --- main --------------------------------------------------------------------

@pytest.fixture
def snap_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(check_api, "SNAP_DIR", str(tmp_path / "snaps"))
    import common
    monkeypatch.setattr(common, "allowed_ids", dict)
    monkeypatch.delenv("COMPAT_AUTH_HEADER", raising=False)
    return tmp_path / "snaps"


def write_snaps(snap_dir, snaps):
    snap_dir.mkdir(parents=True, exist_ok=True)
    (snap_dir / "policies-v1.json").write_text(json.dumps(snaps))


def run_main(monkeypatch, *argv):
    monkeypatch.setattr("sys.argv", ["check_api.py", *argv])
    return check_api.main()


def test_main_record_writes_all_endpoints(server, snap_dir, monkeypatch, capsys):
    server.routes["/api/v1/policies/POL-00000001"] = (200, {"policyNumber": "POL-00000001"})
    assert run_main(monkeypatch, "--record", "--base-url", server.url) == 0
    data = json.loads((snap_dir / "policies-v1.json").read_text())
    assert set(data) == set(check_api.ENDPOINTS)
    assert len(data) == 8
    assert data["/api/v1/policies/POL-00000001"] == {"status": 200, "body": {"policyNumber": "POL-00000001"}}
    assert data["/api/v1/policies/POL-99999999"] == {"status": 404, "body": None}
    assert "recorded 8 responses" in capsys.readouterr().out


def test_main_matching_responses_pass(server, snap_dir, monkeypatch, capsys):
    server.routes["/a"] = (200, {"policyNumber": "A", "lastUpdated": "t1"})
    write_snaps(snap_dir, {"/a": {"status": 200, "body": {"policyNumber": "A", "lastUpdated": "t0"}},
                           "/missing": {"status": 404, "body": None}})
    assert run_main(monkeypatch, "--base-url", server.url) == 0
    assert "== REST API contract (GET /api/v1/policies): PASS" in capsys.readouterr().out


def test_main_status_change_fails_without_body_compare(server, snap_dir, monkeypatch, capsys):
    server.routes["/a"] = (401, None)
    write_snaps(snap_dir, {"/a": {"status": 200, "body": {"x": 1}}})
    assert run_main(monkeypatch, "--base-url", server.url) == 1
    out = capsys.readouterr().out
    assert "FAIL      api:GET /a:status: 200 -> 401" in out
    assert ":removed" not in out


def test_main_body_regression_fails(server, snap_dir, monkeypatch, capsys):
    server.routes["/a"] = (200, {"x": 2})
    write_snaps(snap_dir, {"/a": {"status": 200, "body": {"x": 1}}})
    assert run_main(monkeypatch, "--base-url", server.url) == 1
    assert "api:GET /a.x:value: 1 -> 2" in capsys.readouterr().out


def test_main_base_url_defaults_from_env(server, snap_dir, monkeypatch):
    server.routes["/a"] = (200, {"x": 1})
    write_snaps(snap_dir, {"/a": {"status": 200, "body": {"x": 1}}})
    monkeypatch.setenv("COMPAT_BASE_URL", server.url)
    assert run_main(monkeypatch) == 0


def test_recorded_repo_snapshot_covers_every_endpoint():
    import os
    with open(os.path.join(os.path.dirname(check_api.__file__), "snapshots", "policies-v1.json")) as fh:
        snaps = json.load(fh)
    assert set(snaps) == set(check_api.ENDPOINTS)
    assert snaps["/api/v1/policies/POL-99999999"]["status"] == 404
