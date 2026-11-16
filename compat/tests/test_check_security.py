import unittest
from unittest import mock

from helpers import allowed_changes, run_quiet

import check_security


def diff(*files):
    """Build a -U0 unified diff. files: (path, [added lines]) with path None for deletions."""
    out = []
    for path, lines in files:
        out += [f"diff --git a/{path} b/{path}", f"--- a/{path}", f"+++ b/{path}" if path else "+++ /dev/null", "@@ -0,0 +1 @@"]
        out += ["+" + ln for ln in lines]
        out.append("-removed line pass" + "word=SuperSecret99")
    return "\n".join(out) + "\n"


class AddedLinesTests(unittest.TestCase):
    def test_yields_only_added_lines_with_paths(self):
        d = diff(("src/A.java", ["int a;", "int b;"]), (None, ["ghost"]), (check_security.SELF, ["rule"]))
        with (
            mock.patch.object(check_security, "merge_base", return_value="abc"),
            mock.patch.object(check_security, "git", return_value=d) as git,
        ):
            lines = list(check_security.added_lines())
        self.assertEqual(lines, [("src/A.java", "int a;"), ("src/A.java", "int b;")])
        git.assert_called_once_with("diff", "-U0", "--no-color", "abc...HEAD")


class MainTests(unittest.TestCase):
    def run_main(self, *files, allowed=()):
        with (
            allowed_changes(allowed),
            mock.patch.object(check_security, "merge_base", return_value="abc"),
            mock.patch.object(check_security, "git", return_value=diff(*files)),
        ):
            return run_quiet(check_security.main)

    def test_clean_diff_passes(self):
        code, out = self.run_main(
            ("java-facade/src/main/java/A.java", ["return policy;"]),
            (
                "java-facade/src/main/resources/application.properties",
                [
                    "spring.datasource.password=${DB2_PASSWORD:changeme}",
                    "spring.datasource.password=changeme",
                    "token: ${API_TOKEN}",
                ],
            ),
        )
        self.assertEqual(code, 0, out)
        self.assertIn("== Security non-regression: PASS", out)

    def test_hardcoded_secret_and_private_key_fail(self):
        prop = "java-facade/src/main/resources/application.properties"
        code, out = self.run_main(
            (
                prop,
                [
                    "spring.datasource.pass" + "word=Hunter2Hunter2",
                    "API_" + 'KEY: "abcdef123"',
                    "-----BEGIN RSA PRIVATE " + "KEY-----",
                ],
            )
        )
        self.assertEqual(code, 1)
        self.assertEqual(out.count(f"FAIL      security:hardcoded-secret:{prop}"), 2)
        self.assertIn(f"FAIL      security:private-key:{prop}", out)

    def test_secret_rule_skips_local_and_test_files(self):
        code, out = self.run_main(
            (
                "java-facade/src/main/resources/application-local.properties",
                ["pass" + "word=localdevpass", "spring.h2.console." + "enabled=true", "management.security." + "enabled=false"],
            ),
            ("java-facade/src/test/resources/x.properties", ["sec" + "ret=testsecret1"]),
        )
        self.assertEqual(code, 0, out)

    def test_actuator_h2_console_and_ftp_fail_in_prod_config(self):
        prop = "java-facade/src/main/resources/application.properties"
        code, out = self.run_main(
            (
                prop,
                [
                    "management.security." + "enabled = false",
                    "management.endpoints.web.exposure." + "include=*",
                    "spring.h2.console." + "enabled=TRUE",
                ],
            ),
            ("jcl/SEND.jcl", ["//STEP1 EXEC PGM=" + "FTP,PARM='host'"]),
            ("compat/x.py", ["URL = 'ftp:" + "//example'"]),
        )
        self.assertEqual(code, 1)
        self.assertEqual(out.count(f"security:actuator-exposed:{prop}"), 2)
        self.assertIn(f"security:h2-console-enabled:{prop}", out)
        self.assertIn("security:plaintext-ftp:jcl/SEND.jcl", out)
        self.assertIn("security:plaintext-ftp:compat/x.py", out)

    def test_pii_is_note_and_skipped_for_schema_and_tests(self):
        code, out = self.run_main(
            ("java-facade/src/main/java/Holder.java", ["private String taxId; String ssn_last4;"]),
            ("sql/ddl/create-tables.sql", ["CREDIT_SCORE SMALLINT,"]),
            ("cobol/copybooks/X.cpy", ["05 DATE-OF-BIRTH PIC 9(8)."]),
        )
        self.assertEqual(code, 0)
        self.assertEqual(out.count("NOTE      security:pii-field:"), 1)
        self.assertIn("security:pii-field:java-facade/src/main/java/Holder.java", out)

    def test_message_is_trimmed_to_120_chars(self):
        line = "   password=" + "A" * 200
        code, out = self.run_main(("app/config.yml", [line]))
        self.assertEqual(code, 1)
        reported = out.split("security:hardcoded-" + "secret" + ":app/config.yml: ", 1)[1].splitlines()[0]
        self.assertEqual(reported, line.strip()[:120])

    def test_allow_listed_finding(self):
        fid = "security:actuator-exposed:java-facade/src/main/resources/application.properties"
        code, out = self.run_main(
            ("java-facade/src/main/resources/application.properties", ["management.security." + "enabled=false"]),
            allowed=[{"id": fid, "ticket": "AB-1", "reason": "internal only"}],
        )
        self.assertEqual(code, 0)
        self.assertIn(f"APPROVED  {fid}", out)


if __name__ == "__main__":
    unittest.main()
