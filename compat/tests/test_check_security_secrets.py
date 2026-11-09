"""Tests for the hardcoded-secret rule and path exemptions in check_security.py.

Trigger strings are assembled at runtime so this file's own added lines don't
trip the security gate when it scans the PR that adds them."""
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import common  # noqa: E402
import check_security as sec  # noqa: E402

PW = "pass" + "word"
DS_PW = "spring.datasource." + PW


def kv(key, value, sep="="):
    return f"{key}{sep}{value}"


class HardcodedSecretTest(unittest.TestCase):
    def assertFlags(self, line, expected):
        self.assertEqual(sec.hardcoded_secrets(line), expected, line)

    def test_placeholder_default_is_scanned(self):
        self.assertFlags(kv(PW, "${X:RealSecret99}"), ["RealSecret99"])
        self.assertFlags(kv(DS_PW, "${DB2_" + PW.upper() + ":Acme2026Prod}"), ["Acme2026Prod"])

    def test_short_placeholder_default_is_flagged(self):
        self.assertFlags(kv("tok" + "en", '"${API_TOKEN:abc}"', ": "), ["abc"])

    def test_nested_placeholder_default_is_scanned(self):
        self.assertFlags(kv("sec" + "ret", "${A:${B:nested42}}"), ["nested42"])

    def test_literal_after_placeholder_is_scanned(self):
        self.assertFlags(kv(PW, "${X}RealSecret99"), ["RealSecret99"])

    def test_plain_literal_still_flagged(self):
        self.assertFlags(kv(PW, "hunter2secret"), ["hunter2secret"])
        self.assertFlags(kv("api-" + "key", "'sk_live_123456'", " = "), ["sk_live_123456"])

    def test_safe_values_pass(self):
        for value in ["${X}", "${X:}", "", "changeme", "abc", "${DB2_" + PW.upper() + ":changeme}",
                      "${JWT:CHANGEME}", "${A:${B}}", "${T:#{null}}"]:
            self.assertFlags(kv(PW, value), [])

    def test_existing_application_properties_line_passes(self):
        self.assertFlags(kv(DS_PW, "${DB2_" + PW.upper() + ":changeme}"), [])


class PathExemptionTest(unittest.TestCase):
    def test_real_test_and_local_profile_paths_are_exempt(self):
        for p in ["java-facade/src/test/resources/application.properties",
                  "java-facade/src/main/resources/application-local.properties",
                  "src/test/java/A.java"]:
            self.assertTrue(sec.LOCAL_OR_TEST.search(p), p)

    def test_lookalike_paths_are_not_exempt(self):
        for p in ["java-facade/src/main/resources/src/test/application.properties",
                  "java-facade/src/main/resources/application-local.properties.bak",
                  "deploy/application-local.properties",
                  "a/b/src/test/x.properties"]:
            self.assertFalse(sec.LOCAL_OR_TEST.search(p), p)


class GateEndToEndTest(unittest.TestCase):
    """Runs main() against a throwaway git repo with a base and a PR commit."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.old = common.ROOT, common.BASE_REF
        self.addCleanup(self.restore)
        common.ROOT, common.BASE_REF = self.tmp.name, "base"
        self.git("init", "-q", "-b", "main")
        self.write("README.md", "base\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")
        self.git("branch", "base")

    def restore(self):
        common.ROOT, common.BASE_REF = self.old

    def git(self, *args):
        subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args],
                       cwd=self.tmp.name, check=True, capture_output=True)

    def write(self, path, text):
        full = os.path.join(self.tmp.name, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as fh:
            fh.write(text)

    def gate(self, path, line):
        self.write(path, line + "\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "pr")
        out = StringIO()
        with redirect_stdout(out):
            rc = sec.main()
        return rc, out.getvalue()

    def test_placeholder_default_secret_fails_gate(self):
        rc, out = self.gate("java-facade/src/main/resources/application.properties",
                            kv(PW, "${X:RealSecret99}"))
        self.assertEqual(rc, 1, out)
        self.assertIn("security:hardcoded-" + "secret:java-facade/src/main/resources/application.properties", out)

    def test_changeme_default_passes_gate(self):
        rc, out = self.gate("java-facade/src/main/resources/application.properties",
                            kv(DS_PW, "${DB2_" + PW.upper() + ":changeme}"))
        self.assertEqual(rc, 0, out)

    def test_lookalike_test_path_fails_gate(self):
        rc, out = self.gate("java-facade/src/main/resources/src/test/application.properties",
                            kv(PW, "Acme2026Prod"))
        self.assertEqual(rc, 1, out)

    def test_real_test_path_stays_exempt(self):
        rc, out = self.gate("java-facade/src/test/resources/application.properties",
                            kv(PW, "Acme2026Prod"))
        self.assertEqual(rc, 0, out)


if __name__ == "__main__":
    unittest.main()
