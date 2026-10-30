import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from check_security import RULES, tls_ftp_step_issues

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLAINTEXT_FTP = next(rx for _, rule, rx, _ in RULES if rule == "plaintext-ftp")

SECURE = """//STEP020  EXEC PGM=FTP,PARM='-r TLS (EXIT',
//         COND=(4,LT,STEP010)
//NETRC    DD DSN=ACME.PAS.SECURE.NETRC.ACTUARL,DISP=SHR
//SYSFTPD  DD *
; comment
SECURE_MECHANISM  TLS
SECURE_FTP        REQUIRED
SECURE_CTRLCONN   PRIVATE
SECURE_DATACONN   PRIVATE ; inline comment
/*
//INPUT    DD *
 ACTUARIAL.ACME.LOCAL
 PUT 'A.B' /c.csv
 QUIT
/*
//OUTPUT   DD SYSOUT=*
//STEP030  EXEC PGM=IEFBR14
"""


class TlsFtpGateTests(unittest.TestCase):
    def test_monthly_exposure_step_passes(self):
        with open(os.path.join(ROOT, "jcl", "MONTHLY-EXPOSURE.jcl")) as fh:
            self.assertEqual(tls_ftp_step_issues(fh.read(), "STEP020"), [])

    def test_secure_fixture_passes(self):
        self.assertEqual(tls_ftp_step_issues(SECURE, "STEP020"), [])

    def test_exec_rule(self):
        self.assertTrue(PLAINTEXT_FTP.search("//S EXEC PGM=FTP,PARM='(EXIT'"))
        self.assertTrue(PLAINTEXT_FTP.search("//S EXEC PGM=FTP,PARM='-a TLS (EXIT'"))
        self.assertFalse(PLAINTEXT_FTP.search("//S EXEC PGM=FTP,PARM='-r TLS (EXIT'"))

    def assertIssue(self, text, fragment):
        issues = tls_ftp_step_issues(text, "STEP020")
        self.assertTrue(any(fragment in i for i in issues), issues)

    def test_missing_sysftpd(self):
        text = SECURE.replace("//SYSFTPD  DD *\n", "//SYSFTPD  DD DSN=X.Y(Z),DISP=SHR\n//JUNK     DD *\n")
        self.assertIssue(text, "no in-stream SYSFTPD")

    def test_missing_or_weakened_directive(self):
        self.assertIssue(SECURE.replace("SECURE_FTP        REQUIRED\n", ""), "SECURE_FTP REQUIRED")
        self.assertIssue(SECURE.replace("SECURE_FTP        REQUIRED", "SECURE_FTP        ALLOWED"), "SECURE_FTP REQUIRED")
        self.assertIssue(SECURE.replace("SECURE_DATACONN   PRIVATE ; inline comment",
                                        "SECURE_DATACONN   PRIVATE\nSECURE_DATACONN   CLEAR"), "SECURE_DATACONN PRIVATE")

    def test_concatenated_sysftpd(self):
        self.assertIssue(SECURE.replace("/*\n//INPUT", "/*\n//         DD DSN=X.Y(Z),DISP=SHR\n//INPUT", 1), "concatenated DD")

    def test_inline_login_without_netrc(self):
        self.assertIssue(SECURE.replace("//NETRC    DD DSN=ACME.PAS.SECURE.NETRC.ACTUARL,DISP=SHR\n", ""), "no NETRC")

    def test_downgrade_subcommands(self):
        self.assertIssue(SECURE.replace(" QUIT", " CCC\n QUIT"), "CCC downgrades")
        self.assertIssue(SECURE.replace(" QUIT", " PROT C\n QUIT"), "PROT downgrades")
        self.assertEqual(tls_ftp_step_issues(SECURE.replace(" QUIT", " PROT P\n QUIT"), "STEP020"), [])

    def test_external_input(self):
        text = SECURE.replace("//INPUT    DD *\n ACTUARIAL.ACME.LOCAL\n PUT 'A.B' /c.csv\n QUIT\n/*\n",
                              "//INPUT    DD DSN=X.CMDS,DISP=SHR\n")
        self.assertIssue(text, "in-stream INPUT")


if __name__ == "__main__":
    unittest.main()
