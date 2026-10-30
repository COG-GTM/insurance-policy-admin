import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from check_security import RULES, ftp_step_issues, ftp_steps, security_findings


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
FTP_CONTINUATION_STEP = """//STEP030  EXEC REGION=0M,
//         PGM=FTP,PARM='(EXIT'
//INPUT    DD *
 CLAIMSRV.ACME.LOCAL
 QUIT
/*"""
QUOTED_FTP_TEXT = """//STEP010  EXEC PGM=IKJEFT01,PARM='PGM=FTP'
//SYSTSIN  DD *
  DSN SYSTEM(DBPD)
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

    def test_ftp_step_must_fail_closed_with_exit(self):
        no_exit = SECURE_STEP.replace("PARM='-r TLS (EXIT'", "PARM='-r TLS'")
        self.assertIn(
            ("STEP030", "PARM lacks (EXIT; a refused TLS handshake or failed PUT would not end the step with a non-zero return code"),
            ftp_step_issues(no_exit),
        )
        self.assertEqual(ftp_step_issues(SECURE_STEP), [])

    def test_ftp_program_on_exec_continuation_is_detected(self):
        self.assertIn("STEP030", [name for name, _, _ in ftp_steps(FTP_CONTINUATION_STEP)])
        self.assertIn(
            ("STEP030", "PARM does not require TLS (-r TLS)"),
            ftp_step_issues(FTP_CONTINUATION_STEP),
        )

    def test_ftp_text_inside_quoted_exec_parameter_is_not_detected(self):
        self.assertEqual(ftp_steps(QUOTED_FTP_TEXT), [])
        self.assertEqual(ftp_step_issues(QUOTED_FTP_TEXT), [])

    def test_tls_downgrade_and_missing_data_protection_are_reported(self):
        ccc_fixture = SECURE_STEP.replace(" QUIT", " CCC\n QUIT")
        issues = ftp_step_issues(ccc_fixture)
        self.assertTrue(any(message.endswith("downgrades TLS protection") for _, message in issues))

        missing_data_protection = SECURE_STEP.replace("SECURE_DATACONN   PRIVATE\n", "")
        issues = ftp_step_issues(missing_data_protection)
        self.assertTrue(any("SYSFTPD lacks SECURE_DATACONN PRIVATE" == message for _, message in issues))

    def test_sysftpd_rejects_later_and_inline_overrides(self):
        later_override = SECURE_STEP.replace(
            "SECURE_DATACONN   PRIVATE\n",
            "SECURE_DATACONN   PRIVATE\nSECURE_DATACONN   CLEAR\n",
        )
        self.assertIn(
            ("STEP030", "SYSFTPD overrides SECURE_DATACONN with a non-PRIVATE value"),
            ftp_step_issues(later_override),
        )

        trailing_tokens = SECURE_STEP.replace(
            "SECURE_DATACONN   PRIVATE\n",
            "SECURE_DATACONN   PRIVATE CLEAR\n",
        )
        self.assertIn(
            ("STEP030", "SYSFTPD overrides SECURE_DATACONN with a non-PRIVATE value"),
            ftp_step_issues(trailing_tokens),
        )

        inline_override = SECURE_STEP.replace(
            "SECURE_DATACONN   PRIVATE\n",
            "SECURE_DATACONN   CLEAR ; PRIVATE\n",
        )
        self.assertIn(
            ("STEP030", "SYSFTPD overrides SECURE_DATACONN with a non-PRIVATE value"),
            ftp_step_issues(inline_override),
        )

        inline_comment = SECURE_STEP.replace(
            "SECURE_DATACONN   PRIVATE\n",
            "SECURE_DATACONN   PRIVATE ; comment\n",
        )
        self.assertEqual(ftp_step_issues(inline_comment), [])

    def test_commands_must_come_from_an_instream_input_or_sysin(self):
        dataset_input = SECURE_STEP.replace(
            "//INPUT    DD *\n CLAIMSRV.ACME.LOCAL\n PUT 'ACME.PAS.EXTRACT.POLICY.DAILY' /claims/import/policy_extract.dat\n QUIT\n/*",
            "//INPUT    DD DSN=ACME.PAS.FTP.CMDS,DISP=SHR",
        )
        self.assertIn(
            ("STEP030", "FTP commands must come from an in-stream INPUT DD so they can be reviewed"),
            ftp_step_issues(dataset_input),
        )

        sysin_fixture = SECURE_STEP.replace("//INPUT    DD *", "//SYSIN    DD *")
        self.assertEqual(ftp_step_issues(sysin_fixture), [])

        sysin_commands = SECURE_STEP.replace(
            " QUIT\n/*",
            " QUIT\n/*\n//SYSIN    DD *\n CLAIMSRV.ACME.LOCAL\n CCC\n/*",
        )
        self.assertIn(
            ("STEP030", "SYSIN line 2 downgrades TLS protection"),
            ftp_step_issues(sysin_commands),
        )

        sysin_dataset = SECURE_STEP.replace(
            " QUIT\n/*",
            " QUIT\n/*\n//SYSIN    DD DSN=X.Y,DISP=SHR",
        )
        self.assertIn(
            ("STEP030", "SYSIN must be in-stream so FTP commands can be reviewed"),
            ftp_step_issues(sysin_dataset),
        )

    def test_inline_password_is_reported_without_echoing_content(self):
        fixture = SECURE_STEP.replace(" PUT 'ACME.PAS", " HUNTER2PW\n PUT 'ACME.PAS")
        issues = ftp_step_issues(fixture)
        messages = [message for _, message in issues]
        self.assertTrue(any("INPUT line 2 is not an FTP subcommand" in message for message in messages))
        self.assertNotIn("HUNTER2PW", " ".join(messages))

    def test_sysftpd_and_input_concatenated_datasets_are_rejected(self):
        sysftpd_concat = SECURE_STEP.replace(
            "/*\n//INPUT",
            "/*\n//         DD DSN=ACME.FTP.DATA,DISP=SHR\n//INPUT",
        )
        self.assertIn(
            ("STEP030", "SYSFTPD has a concatenated dataset; TLS policy/commands must be fully in-stream"),
            ftp_step_issues(sysftpd_concat),
        )

        input_concat = SECURE_STEP.replace(
            " QUIT\n/*",
            " QUIT\n/*\n//         DD DSN=ACME.PAS.FTP.EXTRA,DISP=SHR",
        )
        self.assertIn(
            ("STEP030", "INPUT has a concatenated dataset; TLS policy/commands must be fully in-stream"),
            ftp_step_issues(input_concat),
        )

    def test_plaintext_ftp_rule_detects_only_unsecured_ftp(self):
        rule = next(rx for _, name, rx, _ in RULES if name == "plaintext-ftp")
        self.assertIsNotNone(rule.search("//S EXEC PGM=FTP,PARM='(EXIT'"))
        self.assertIsNotNone(rule.search("ftp://host/x"))
        self.assertIsNone(rule.search("//S EXEC PGM=FTP,PARM='-r TLS (EXIT'"))

    def test_symbolic_program_name_is_checked_as_ftp(self):
        via_set = "//         SET XFER=FTP\n" + OLD_INSECURE_STEP.replace("PGM=FTP", "PGM=&XFER")
        via_proc = "//SENDPRC  PROC P=FTP\n" + OLD_INSECURE_STEP.replace("PGM=FTP", "PGM=&P.")
        unresolved = OLD_INSECURE_STEP.replace("PGM=FTP", "PGM=&UNKNOWN")
        for text in (via_set, via_proc, unresolved):
            self.assertIn(("STEP030", "PARM does not require TLS (-r TLS)"), ftp_step_issues(text))
        secure = "//         SET XFER=FTP\n" + SECURE_STEP.replace("PGM=FTP", "PGM=&XFER")
        self.assertEqual(ftp_step_issues(secure), [])
        other = "//         SET XFER=IEBGENER\n//STEP1    EXEC PGM=&XFER\n//SYSUT1   DD DUMMY"
        self.assertEqual(ftp_steps(other), [])

    def test_gate_accepts_secure_step_with_parm_on_continuation(self):
        step = SECURE_STEP.replace(
            "//STEP030  EXEC PGM=FTP,PARM='-r TLS (EXIT',\n//         COND=(4,LT)",
            "//STEP030  EXEC PGM=FTP,\n//         PARM='-r TLS (EXIT',COND=(4,LT)",
        )
        self.assertNotEqual(step, SECURE_STEP)
        added = [("jcl/NEW.jcl", line) for line in step.splitlines()]
        self.assertEqual(security_findings(added, lambda path: step), [])

    def test_gate_fails_insecure_jcl_step_and_ftp_urls(self):
        added = [("jcl/NEW.jcl", line) for line in OLD_INSECURE_STEP.splitlines()]
        ids = [fid for _, fid, _ in security_findings(added, lambda path: OLD_INSECURE_STEP)]
        self.assertIn("security:ftp-step-insecure:jcl/NEW.jcl:STEP030", ids)
        self.assertNotIn("security:plaintext-ftp:jcl/NEW.jcl", ids)
        url_ids = [fid for _, fid, _ in security_findings(
            [("jcl/NEW.jcl", "//* see ftp://claimsrv/x"), ("scripts/push.sh", "ftp -n host # PGM=FTP")],
            lambda path: "",
        )]
        self.assertEqual(url_ids, ["security:plaintext-ftp:jcl/NEW.jcl", "security:plaintext-ftp:scripts/push.sh"])


if __name__ == "__main__":
    unittest.main()
