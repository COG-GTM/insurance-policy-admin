import unittest
from unittest import mock

from support import captured_findings, ids

import check_security


def diff(*files):
    """files: (path, [added lines]); path None renders a deletion (+++ /dev/null)."""
    out = []
    for path, lines in files:
        out.append(f"diff --git a/{path} b/{path}")
        out.append(f"--- a/{path}")
        out.append("+++ /dev/null" if path is None else f"+++ b/{path}")
        out.append("@@ -1,0 +1,%d @@" % len(lines))
        out.extend("+" + line for line in lines)
    return "\n".join(out) + "\n"


class AddedLinesTests(unittest.TestCase):
    def added(self, text):
        with mock.patch.object(check_security, "merge_base", return_value="abc123"), \
                mock.patch.object(check_security, "git", return_value=text) as git:
            lines = list(check_security.added_lines())
        git.assert_called_once_with("diff", "-U0", "--no-color", "abc123...HEAD")
        return lines

    def test_yields_added_lines_with_their_paths(self):
        text = diff(("a.properties", ["x=1", "y=2"]), ("b/c.java", ["int z;"]))
        self.assertEqual(self.added(text), [("a.properties", "x=1"), ("a.properties", "y=2"), ("b/c.java", "int z;")])

    def test_removed_and_context_lines_are_ignored(self):
        text = "+++ b/f.txt\n@@ -1 +1 @@\n-old secret\n context\n+new\n"
        self.assertEqual(self.added(text), [("f.txt", "new")])

    def test_deleted_files_and_the_checker_itself_are_skipped(self):
        text = diff((None, ["password=hunter2hunter2"]), ("compat/check_security.py", ["token = abcdefgh"]))
        self.assertEqual(self.added(text), [])


class RuleTests(unittest.TestCase):
    def scan(self, *pairs):
        with mock.patch.object(check_security, "added_lines", return_value=list(pairs)), \
                captured_findings(check_security) as findings:
            code = check_security.main()
        return code, findings

    def test_clean_change_passes(self):
        self.assertEqual(self.scan(("src/main/java/A.java", "int x = 1;")), (0, []))

    def test_private_key_fails_everywhere_including_tests(self):
        code, findings = self.scan(("java-facade/src/test/resources/key.pem", "-----BEGIN RSA PRIVATE KEY-----"))
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {"security:private-key:java-facade/src/test/resources/key.pem"})

    def test_hardcoded_secret_in_main_config_fails(self):
        path = "java-facade/src/main/resources/application.properties"
        for line in ("spring.datasource.password=Sup3rS3cret", "api_key: 'abcdef123456'", "TOKEN = \"tok_live_9999\""):
            with self.subTest(line=line):
                code, findings = self.scan((path, line))
                self.assertEqual(code, 1)
                self.assertEqual(ids(findings), {f"security:hardcoded-secret:{path}"})
                self.assertEqual(findings[0][2], line)

    def test_placeholders_and_short_values_are_not_secrets(self):
        path = "java-facade/src/main/resources/application.properties"
        for line in ("spring.datasource.password=${DB2_PASSWORD}", "password=changeme", "password=", "secret=abc"):
            with self.subTest(line=line):
                self.assertEqual(self.scan((path, line)), (0, []))

    def test_secrets_in_local_profile_and_tests_are_allowed(self):
        code, findings = self.scan(
            ("java-facade/src/main/resources/application-local.properties", "spring.datasource.password=localpass1"),
            ("java-facade/src/test/java/FooTest.java", "String token = \"testtoken123\";"),
        )
        self.assertEqual((code, findings), (0, []))

    def test_exposed_actuator_fails_outside_local_and_test(self):
        main = "java-facade/src/main/resources/application.properties"
        code, findings = self.scan(
            (main, "management.security.enabled=false"),
            (main, "management.endpoints.web.exposure.include=*"),
            ("java-facade/src/test/resources/application.properties", "management.security.enabled=false"),
        )
        self.assertEqual(code, 1)
        self.assertEqual([f for _, f, _ in findings], [f"security:actuator-exposed:{main}"] * 2)

    def test_h2_console_only_allowed_in_local_profile(self):
        code, findings = self.scan(("svc/src/main/resources/application.properties", "spring.h2.console.enabled=true"))
        self.assertEqual(ids(findings), {"security:h2-console-enabled:svc/src/main/resources/application.properties"})
        self.assertEqual(self.scan(("svc/src/main/resources/application-local.properties", "spring.h2.console.enabled=true")), (0, []))

    def test_plaintext_ftp_fails(self):
        code, findings = self.scan(("jcl/SEND.jcl", "//STEP02   EXEC PGM=FTP,PARM='host'"), ("docs/x.md", "see ftp://files.acme.local"))
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {"security:plaintext-ftp:jcl/SEND.jcl", "security:plaintext-ftp:docs/x.md"})

    def test_pii_is_a_note_in_code_and_ignored_in_schema(self):
        code, findings = self.scan(
            ("java-facade/src/main/java/Holder.java", "private String ssnLast4;"),
            ("java-facade/src/main/java/Holder2.java", "private String tax_id;"),
            ("sql/ddl/create-tables.sql", "SSN_LAST4 CHAR(4),"),
            ("cobol/copybooks/CUSTOMER-RECORD.cpy", "05 CUST-DATE-OF-BIRTH PIC 9(8)."),
        )
        self.assertEqual(code, 0)
        self.assertEqual(findings, [
            ("NOTE", "security:pii-field:java-facade/src/main/java/Holder.java", "private String ssnLast4;"),
            ("NOTE", "security:pii-field:java-facade/src/main/java/Holder2.java", "private String tax_id;"),
        ])

    def test_long_lines_are_truncated_in_message(self):
        line = "password=" + "x" * 200
        _, findings = self.scan(("app.properties", line))
        self.assertEqual(len(findings[0][2]), 120)


if __name__ == "__main__":
    unittest.main()
