"""Runs pas/scripts/cutover-smoke.sh in shared-environment mode against a stub PAS API."""
import json
import os
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCRIPT = os.path.join(ROOT, "pas", "scripts", "cutover-smoke.sh")
READ, OPS = "read-jwt", "ops-jwt"
POLICY = {
    "policyNumber": "POL-00000001",
    "totalPremium": 1250.00,
    "coverages": [{"coverageType": "LIAB", "premium": 850.00}, {"coverageType": "COMP", "premium": 400.00}],
}


class StubPas(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, code, body=None):
        data = json.dumps(body if body is not None else {}).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        token = self.headers.get("Authorization", "").removeprefix("Bearer ")
        path = self.path
        if path == "/actuator/health/readiness":
            return self.reply(200, {"status": "UP"})
        if path == "/actuator/prometheus":
            return self.reply({"": 401, OPS: 200}.get(token, 403))
        if path.startswith("/api/v2/alerts"):
            return self.reply(200, [])
        if path.startswith("/api/v1/policies?"):
            return self.reply(400 if token == READ else 401)
        if path.startswith("/api/v1/policies/"):
            if not token:
                return self.reply(401)
            if token != READ:
                return self.reply(403)
            if path.endswith("/" + POLICY["policyNumber"]):
                return self.reply(200, POLICY)
            return self.reply(404)
        return self.reply(404)


class CutoverSmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), StubPas)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def run_smoke(self, **extra):
        env = {k: v for k, v in os.environ.items() if not k.startswith("PAS_")}
        env.update(PAS_API_URL=self.url, PAS_MGMT_URL=self.url, PAS_ALERTMANAGER_URL=self.url,
                   PAS_READ_JWT=READ, PAS_OPS_JWT=OPS)
        env.update(extra)
        return subprocess.run(["bash", SCRIPT], env=env, capture_output=True, text=True, timeout=60)

    def test_passes_without_a_known_policy(self):
        result = self.run_smoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("all automated checks passed", result.stdout)

    def test_sm01_compares_the_policy_total_premium_with_db2(self):
        result = self.run_smoke(PAS_SMOKE_POLICY="POL-00000001", PAS_SMOKE_PREMIUM="1250.00")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("with the DB2 premium", result.stdout)

    def test_sm01_fails_when_the_premium_differs(self):
        for premium in ("1250.01", "850.00"):
            result = self.run_smoke(PAS_SMOKE_POLICY="POL-00000001", PAS_SMOKE_PREMIUM=premium)
            self.assertEqual(result.returncode, 1, premium)
            self.assertIn("SM-01 premium of POL-00000001 differs from DB2", result.stderr)

    def test_shared_environment_requires_alertmanager(self):
        result = self.run_smoke(PAS_ALERTMANAGER_URL="")
        self.assertEqual(result.returncode, 1)
        self.assertIn("PAS_ALERTMANAGER_URL is required", result.stderr)


if __name__ == "__main__":
    unittest.main()
