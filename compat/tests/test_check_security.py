import unittest
from unittest import mock

import check_security

from tests import support

# Trigger strings are assembled at runtime so this file does not trip the
# security gate it is testing.
PW = "pass" + "word"
PROD_PROPS = "java-facade/src/main/resources/application.properties"


def scan(lines):
    cap = support.FindingsCapture()
    with (
        mock.patch.object(check_security, "added_lines", lambda: iter(lines)),
        mock.patch.object(check_security, "report", cap),
    ):
        rc = check_security.main()
    return rc, cap


class RuleTests(unittest.TestCase):
    def assertFlags(self, path, line, rule, severity="FAIL"):
        rc, cap = scan([(path, line)])
        self.assertEqual(
            cap.findings, [(severity, f"security:{rule}:{path}", line.strip())]
        )
        self.assertEqual(rc, 1 if severity == "FAIL" else 0)

    def assertClean(self, path, line):
        rc, cap = scan([(path, line)])
        self.assertEqual((rc, cap.findings), (0, []))

    def test_private_key_is_flagged_everywhere(self):
        self.assertFlags(
            "src/test/resources/key.pem",
            "-----BEGIN " + "RSA PRIVATE KEY-----",
            "private-key",
        )

    def test_hardcoded_secret_in_production_config(self):
        self.assertFlags(
            PROD_PROPS, "spring.datasource." + PW + "=Sup3rS3cret", "hardcoded-secret"
        )
        self.assertFlags(
            "app/config.yml", "api" + "_key: 'abcdef123456'", "hardcoded-secret"
        )

    def test_placeholder_default_and_short_values_are_not_secrets(self):
        self.assertClean(PROD_PROPS, "spring.datasource." + PW + "=${DB2_PASSWORD}")
        self.assertClean(PROD_PROPS, "spring.datasource." + PW + "=changeme")
        self.assertClean(PROD_PROPS, "token=abc")

    def test_secrets_allowed_in_local_profile_and_test_sources(self):
        self.assertClean(
            "java-facade/src/main/resources/application-local.properties",
            PW + "=Sup3rS3cret",
        )
        self.assertClean(
            "java-facade/src/test/resources/application.properties", PW + "=Sup3rS3cret"
        )

    def test_actuator_exposure_outside_local_or_test(self):
        self.assertFlags(
            PROD_PROPS, "management.security.enabled" + "=false", "actuator-exposed"
        )
        self.assertFlags(
            PROD_PROPS,
            "management.endpoints.web.exposure.include" + " = *",
            "actuator-exposed",
        )
        self.assertClean(
            "java-facade/src/main/resources/application-local.properties",
            "management.security.enabled" + "=false",
        )

    def test_h2_console_outside_local_profile(self):
        self.assertFlags(
            PROD_PROPS, "spring.h2.console.enabled" + "=true", "h2-console-enabled"
        )
        self.assertClean(PROD_PROPS, "spring.h2.console.enabled" + "=false")

    def test_plaintext_ftp_in_jcl_or_urls(self):
        self.assertFlags("jcl/SEND.jcl", "//STEP1 EXEC PGM=" + "FTP", "plaintext-ftp")
        self.assertFlags(
            "app/feed.properties", "feed.url=ftp" + "://host/out", "plaintext-ftp"
        )
        self.assertClean("jcl/SEND.jcl", "//STEP1 EXEC PGM=SFTPBAT")

    def test_pii_field_is_a_note_and_skipped_in_schema_files(self):
        self.assertFlags(
            "java-facade/src/main/java/Holder.java",
            "private String taxId;",
            "pii-field",
            "NOTE",
        )
        self.assertClean("sql/ddl/create-tables.sql", "TAX_ID CHAR(11),")
        self.assertClean(
            "cobol/copybooks/CUSTOMER-RECORD.cpy", "05 CUST-DATE-OF-BIRTH PIC 9(8)."
        )

    def test_finding_message_is_stripped_and_truncated(self):
        line = "   api_key=" + "A" * 200
        _, cap = scan([(PROD_PROPS, line)])
        self.assertEqual(len(cap.findings[0][2]), 120)
        self.assertTrue(cap.findings[0][2].startswith("api_key="))

    def test_no_added_lines_passes(self):
        rc, cap = scan([])
        self.assertEqual((rc, cap.findings), (0, []))
        self.assertEqual(cap.check_name, "Security non-regression")


class AddedLinesTests(unittest.TestCase):
    DIFF_LINES = (
        "diff --git a/app.properties b/app.properties",
        "--- a/app.properties",
        "+++ b/app.properties",
        "@@ -1,0 +2,2 @@",
        "+first added",
        "+second added",
        "-removed line",
        "diff --git a/compat/check_security.py b/compat/check_security.py",
        "--- a/compat/check_security.py",
        "+++ b/compat/check_security.py",
        "@@ -5,0 +6 @@",
        "+rule definition",
        "diff --git a/gone.txt b/gone.txt",
        "--- a/gone.txt",
        "+++ /dev/null",
        "@@ -1 +0,0 @@",
        "-bye",
        "diff --git a/b.jcl b/b.jcl",
        "+++ b/b.jcl",
        "@@ -0,0 +1 @@",
        "+//X DD *",
    )
    DIFF = "\n".join(DIFF_LINES)

    def test_yields_added_lines_per_file_and_skips_self_and_deletions(self):
        calls = []

        def fake_git(*args, **kw):
            calls.append(args)
            return self.DIFF

        with (
            mock.patch.object(check_security, "merge_base", lambda: "abc123"),
            mock.patch.object(check_security, "git", fake_git),
        ):
            lines = list(check_security.added_lines())
        self.assertEqual(
            lines,
            [
                ("app.properties", "first added"),
                ("app.properties", "second added"),
                ("b.jcl", "//X DD *"),
            ],
        )
        self.assertEqual(calls, [("diff", "-U0", "--no-color", "abc123...HEAD")])


if __name__ == "__main__":
    unittest.main()
