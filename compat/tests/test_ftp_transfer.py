import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from check_security import RULES, ftp_step_issues, ftp_steps


REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(__file__)))
OLD_INSECURE_STEP = """//*-------------------------------------------------------------------
//* STEP030 - FTP extract files to Claims server
//*-------------------------------------------------------------------
//STEP030  EXEC PGM=FTP,PARM='(EXIT',
//         COND=(4,LT)
//INPUT    DD *
 CLAIMSRV.ACME.LOCAL
 ACMEFTP
 PUT 'ACME.PAS.EXTRACT.POLICY.DAILY' /claims/import/policy_extract.dat
 PUT 'ACME.PAS.EXTRACT.COVERAGE.DAILY' /claims/import/coverage_extract.dat
 QUIT
/*"""
SECURE_STEP = """//STEP030  EXEC PGM=FTP,PARM='-r TLS (EXIT',
//         COND=(4,LT)
//NETRC    DD DSN=ACME.PAS.FTPS.NETRC,DISP=SHR
//SYSFTPD  DD *
SECURE_FTP        REQUIRED
SECURE_MECHANISM  TLS
SECURE_CTRLCONN   PRIVATE
SECURE_DATACONN   PRIVATE
/*
//INPUT    DD *
 CLAIMSRV.ACME.LOCAL
 PUT 'ACME.PAS.EXTRACT.POLICY.DAILY' /claims/import/policy_extract.dat
 QUIT
/*"""


def read_daily_extract():
    with open(os.path.join(REPO_ROOT, "jcl", "DAILY-EXTRACT.jcl"), encoding="utf-8") as stream:
        return stream.read()


class FtpTransferTests(unittest.TestCase):
    def test_daily_extract_requires_tls_and_has_no_ftp_issues(self):
        text = read_daily_extract()
        self.assertIn("STEP030", [name for name, _, _ in ftp_steps(text)])
        self.assertEqual(ftp_step_issues(text), [])

    def test_daily_extract_corrects_encryption_history(self):
        self.assertNotIn("Added encryption step", read_daily_extract())

    def test_original_insecure_step_reports_tls_login_and_policy_issues(self):
        issues = ftp_step_issues(OLD_INSECURE_STEP)
        messages = [message for _, message in issues]
        self.assertTrue(any("PARM does not require TLS" in message for message in messages))
        self.assertTrue(any("SYSFTPD" in message for message in messages))
        self.assertTrue(any("no NETRC DD" in message for message in messages))
        self.assertIn(("STEP030", "INPUT line 2 is not an FTP subcommand (inline login/credential?)"), issues)

    def test_tls_downgrade_and_missing_data_protection_are_reported(self):
        ccc_fixture = SECURE_STEP.replace(" QUIT", " CCC\n QUIT")
        issues = ftp_step_issues(ccc_fixture)
        self.assertTrue(any(message.endswith("downgrades TLS protection") for _, message in issues))

        missing_data_protection = SECURE_STEP.replace("SECURE_DATACONN   PRIVATE\n", "")
        issues = ftp_step_issues(missing_data_protection)
        self.assertTrue(any("SYSFTPD lacks SECURE_DATACONN PRIVATE" == message for _, message in issues))

    def test_inline_password_is_reported_without_echoing_content(self):
        fixture = SECURE_STEP.replace(" PUT 'ACME.PAS", " HUNTER2PW\n PUT 'ACME.PAS")
        issues = ftp_step_issues(fixture)
        messages = [message for _, message in issues]
        self.assertTrue(any("INPUT line 2 is not an FTP subcommand" in message for message in messages))
        self.assertNotIn("HUNTER2PW", " ".join(messages))

    def test_plaintext_ftp_rule_detects_only_unsecured_ftp(self):
        rule = next(rx for _, name, rx, _ in RULES if name == "plaintext-ftp")
        self.assertIsNotNone(rule.search("//S EXEC PGM=FTP,PARM='(EXIT'"))
        self.assertIsNotNone(rule.search("ftp://host/x"))
        self.assertIsNone(rule.search("//S EXEC PGM=FTP,PARM='-r TLS (EXIT'"))


if __name__ == "__main__":
    unittest.main()
