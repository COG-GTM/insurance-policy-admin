import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import check_security as sec  # noqa: E402

# Fixture strings are split so this file's own added lines don't trip the security gate in CI.
PW = "pass" + "word"
SECRET_LINE = "spring.datasource." + PW + "=S3cretPass!"
KEY_HEADER = "-----BEGIN " + "PRIVATE KEY-----"
RSA_KEY_HEADER = "-----BEGIN " + "RSA PRIVATE KEY-----"
ACTUATOR_SECURITY_OFF = "management.security." + "enabled=false"
ACTUATOR_EXPOSE_ALL = "management.endpoints.web.exposure." + "include=*"
H2_CONSOLE_ON = "spring.h2.console." + "enabled=true"
FTP_STEP = "//STEP1 EXEC PGM=" + "FTP,PARM='host'"
FTP_URL = "url=ftp:" + "//host/x"


def diff(*files):
    """files: (path, [added lines]) -> unified diff text as produced by git diff -U0."""
    out = []
    for path, lines in files:
        out += [f"diff --git a/{path} b/{path}", f"--- a/{path}", f"+++ b/{path}", "@@ -1,0 +1,1 @@"]
        out += ["+" + line for line in lines]
        out.append("-removed line " + PW + "=supersecretvalue")
    return "\n".join(out) + "\n"


class SecurityTests(unittest.TestCase):
    def run_main(self, diff_text):
        captured = {}

        def fake_report(name, findings):
            captured["findings"] = findings
            return 1 if any(s == "FAIL" for s, _, _ in findings) else 0
        with mock.patch.object(sec, "git", return_value=diff_text) as git, \
                mock.patch.object(sec, "merge_base", return_value="abc123"), \
                mock.patch.object(sec, "report", fake_report):
            code = sec.main()
        git.assert_called_once_with("diff", "-U0", "--no-color", "abc123...HEAD")
        return code, captured["findings"]

    def ids(self, diff_text):
        return {fid: sev for sev, fid, _ in self.run_main(diff_text)[1]}

    def test_added_lines_parses_paths_and_skips_removed_and_deleted(self):
        text = diff(("a.txt", ["one", "two"])) + "--- a/gone.txt\n+++ /dev/null\n+ghost\n"
        with mock.patch.object(sec, "git", return_value=text), mock.patch.object(sec, "merge_base", return_value="m"):
            self.assertEqual(list(sec.added_lines()), [("a.txt", "one"), ("a.txt", "two")])

    def test_checker_itself_is_excluded(self):
        self.assertEqual(self.ids(diff((sec.SELF, [RSA_KEY_HEADER]))), {})

    def test_clean_diff_passes(self):
        self.assertEqual(self.run_main(diff(("src/main/App.java", ["int x = 1;"]))), (0, []))

    def test_private_key_fails_everywhere(self):
        ids = self.ids(diff(("x/src/test/key.pem", [KEY_HEADER])))
        self.assertEqual(ids, {"security:private-key:x/src/test/key.pem": "FAIL"})

    def test_hardcoded_secret_fails_in_main_config(self):
        path = "java-facade/src/main/resources/application.properties"
        code, findings = self.run_main(diff((path, [SECRET_LINE])))
        self.assertEqual(code, 1)
        self.assertEqual(findings, [("FAIL", f"security:hardcoded-secret:{path}", SECRET_LINE)])

    def test_secret_variants(self):
        path = "app/config.yml"
        self.assertIn(f"security:hardcoded-secret:{path}", self.ids(diff((path, ["api" + '_key: "abcdef123456"']))))
        self.assertIn(f"security:hardcoded-secret:{path}", self.ids(diff((path, ["TOK" + "EN = xyz7890abc"]))))

    def test_placeholders_and_short_values_not_secrets(self):
        lines = ["password=${DB2_PASSWORD}", "password=changeme", "password=abc", "password = ${X:changeme}"]
        self.assertEqual(self.ids(diff(("app/application.properties", lines))), {})

    def test_secret_allowed_in_local_and_test_files(self):
        lines = [PW + "=S3cretPass!"]
        self.assertEqual(self.ids(diff(("java-facade/src/main/resources/application-local.properties", lines),
                                       ("java-facade/src/test/resources/application.properties", lines))), {})

    def test_actuator_exposure_rules(self):
        path = "svc/src/main/resources/application.properties"
        ids = self.ids(diff((path, [ACTUATOR_SECURITY_OFF, ACTUATOR_EXPOSE_ALL])))
        self.assertEqual(ids, {f"security:actuator-exposed:{path}": "FAIL"})
        self.assertEqual(self.ids(diff(("svc/src/test/resources/application.properties",
                                        [ACTUATOR_SECURITY_OFF]))), {})

    def test_h2_console_only_allowed_in_local_profile(self):
        self.assertEqual(self.ids(diff(("svc/application.properties", [H2_CONSOLE_ON]))),
                         {"security:h2-console-enabled:svc/application.properties": "FAIL"})
        self.assertEqual(self.ids(diff(("svc/application-local.properties", [H2_CONSOLE_ON]))), {})

    def test_plaintext_ftp(self):
        ids = self.ids(diff(("jcl/X.jcl", [FTP_STEP]), ("cfg.txt", [FTP_URL])))
        self.assertEqual(ids, {"security:plaintext-ftp:jcl/X.jcl": "FAIL", "security:plaintext-ftp:cfg.txt": "FAIL"})
        self.assertEqual(self.ids(diff(("jcl/Y.jcl", ["//STEP1 EXEC PGM=FTPS", "url=sftp://host"]))), {})

    def test_pii_is_note_and_skipped_for_schema_files(self):
        code, findings = self.run_main(diff(("svc/Customer.java", ["private String taxId;", "String ssn_last4;"])))
        self.assertEqual(code, 0)
        self.assertEqual([f[:2] for f in findings], [("NOTE", "security:pii-field:svc/Customer.java")] * 2)
        self.assertEqual(self.ids(diff(("sql/ddl/t.sql", ["DATE_OF_BIRTH DATE,"]),
                                       ("cobol/copybooks/C.cpy", ["05 CREDIT-SCORE PIC 9(3)."]),
                                       ("compat/x.py", ["credit_score"]))), {})

    def test_long_lines_truncated_in_message(self):
        _, findings = self.run_main(diff(("a.cfg", ["   secret=" + "a" * 200])))
        self.assertEqual(len(findings[0][2]), 120)
        self.assertTrue(findings[0][2].startswith("secret="))


if __name__ == "__main__":
    unittest.main()
