"""POLEND audit attribution tests.

EXEC CICS/SQL blocks are replaced with stubs driven by environment
variables and the program is compiled and run under GnuCOBOL. Set
POLEND_SRC to run the same checks against another revision.
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POLEND_PATH = REPO_ROOT / "cobol" / "programs" / "POLEND.cbl"
COPYBOOK_PATH = REPO_ROOT / "cobol" / "copybooks"

TEST_FIELDS = """       01  SQLCODE PIC S9(9) COMP.
       01  EIBTRMID PIC X(04) VALUE 'T042'.
       01  EIBTRNID PIC X(04) VALUE 'PEND'.
       01  T-TEXT PIC X(20).
       01  T-AMT PIC -9(9).99"""


def accept(target, var):
    return (f"           ACCEPT {target}\n"
            f"               FROM ENVIRONMENT '{var}'")


def set_code(target, var):
    return f"""           MOVE SPACES TO T-TEXT
{accept('T-TEXT', var)}
           IF T-TEXT = SPACES
               MOVE 0 TO {target}
           ELSE
               COMPUTE {target} = FUNCTION NUMVAL(T-TEXT)
           END-IF"""


def operand(token):
    """COBOL DISPLAY operand for a SQL value: host var or literal."""
    return token[1:] if token.startswith(":") else token


def stub(match):
    block = " ".join(match.group(0).upper().split())
    if "INCLUDE SQLCA" in block:
        return TEST_FIELDS
    if "CICS ASKTIME" in block:
        return accept("WS-CURRENT-DATE", "T_DATE")
    if "CICS FORMATTIME" in block:
        return "           CONTINUE"
    if "CICS ASSIGN" in block:
        return "\n".join([
            accept("WS-USERID", "T_USERID"),
            set_code("WS-RESPONSE-CODE", "T_ASSIGN_RESP"),
            "           DISPLAY 'ASSIGN'",
        ])
    if "CICS RECEIVE" in block:
        # Map fields the endorsement screen supplies
        return "\n".join([
            "           MOVE 0 TO WS-RESPONSE-CODE",
            accept("POLICY-NUMBER", "T_POLICY_NUMBER"),
            accept("WS-ENDORSEMENT-TYPE", "T_TYPE"),
            accept("T-TEXT", "T_ADJUST"),
            "           COMPUTE WS-PREMIUM-ADJUST =",
            "               FUNCTION NUMVAL(T-TEXT)",
        ])
    if "SELECT POLICY_NUMBER, POLICY_STATUS" in block:
        return "\n".join([
            "           MOVE 'AUT' TO POLICY-TYPE",
            "           MOVE 'H123' TO POLICY-HOLDER-ID",
            "           MOVE 'AC' TO POLICY-STATUS",
            "           MOVE 20260101 TO POLICY-EFFECTIVE-DATE",
            "           MOVE 20270101 TO POLICY-EXPIRY-DATE",
            "           MOVE 1000.00 TO POLICY-TOTAL-PREMIUM",
            set_code("SQLCODE", "T_SELECT_SQLCODE"),
        ])
    if "MAX(ENDORSEMENT_SEQ)" in block:
        return "           MOVE 7 TO WS-ENDORSEMENT-SEQ\n" + set_code(
            "SQLCODE", "T_SEQ_SQLCODE")
    if "INSERT INTO ENDORSEMENTS" in block:
        by = re.search(r"CURRENT TIMESTAMP, (\S+?) ?\)", block).group(1)
        return set_code("SQLCODE", "T_INSERT_SQLCODE") + f"""
           DISPLAY 'INSERT PROCESSED_BY=[' {operand(by)} ']'"""
    if "UPDATE POLICIES" in block:
        by = re.search(r"UPDATED_BY = (\S+)", block).group(1)
        return set_code("SQLCODE", "T_UPDATE_SQLCODE") + f"""
           MOVE POLICY-TOTAL-PREMIUM TO T-AMT
           DISPLAY 'UPDATE UPDATED_BY=[' {operand(by)} '] PREM=' T-AMT"""
    if "SYNCPOINT ROLLBACK" in block:
        return ("           MOVE 0 TO WS-RESPONSE-CODE\n"
                "           DISPLAY 'ROLLBACK'")
    if "CICS ABEND" in block:
        return "           DISPLAY 'ABEND'\n           STOP RUN"
    if "CICS WRITEQ TS" in block:
        src = re.search(r"FROM\((\S+?)\)", block).group(1)
        return "\n".join([
            "           DISPLAY 'AUDIT LEN=' LENGTH OF " + src,
            "           DISPLAY 'AUDIT POLICY-LEN=' LENGTH OF POLICY-RECORD",
            "           DISPLAY 'AUDIT REC=[' " + src + " ']'",
            set_code("WS-RESPONSE-CODE", "T_WRITEQ_RESP"),
        ])
    if "CICS SEND TEXT" in block:
        return "           DISPLAY 'ERROR=' WS-ERROR-MSG"
    if "CICS SEND" in block:
        return "           DISPLAY 'CONFIRM'"
    if "CICS RETURN" in block:
        return "           DISPLAY 'RETURN'\n           STOP RUN"
    raise AssertionError(f"No stub for EXEC block: {match.group(0)}")


def make_test_source(source):
    pattern = re.compile(r"^ {7,}EXEC\b.*?\bEND-EXEC\b", re.M | re.S)
    source, count = pattern.subn(stub, source)
    if count == 0:
        raise AssertionError("POLEND source contains no EXEC blocks")
    return re.sub(r"DFHRESP\(NORMAL\)", "0", source, flags=re.I)


class PolendAuditUserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cobc = shutil.which("cobc")
        if cobc is None:
            if "CI" in os.environ:
                raise RuntimeError("GnuCOBOL (cobc) is required in CI")
            raise unittest.SkipTest("GnuCOBOL (cobc) is not installed")
        source = Path(os.environ.get("POLEND_SRC", POLEND_PATH)).read_text()
        cls.tmp = tempfile.TemporaryDirectory(prefix="polend-")
        src = Path(cls.tmp.name) / "POLEND.cbl"
        src.write_text(make_test_source(source))
        cls.binary = Path(cls.tmp.name) / "polend"
        result = subprocess.run(
            [cobc, "-x", "-I", str(COPYBOOK_PATH), "-o", str(cls.binary),
             str(src)],
            text=True, capture_output=True)
        if result.returncode != 0:
            raise RuntimeError(f"cobc failed:\n{result.stdout}{result.stderr}")

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "tmp"):
            cls.tmp.cleanup()

    def run_polend(self, userid="MALLORY", **overrides):
        env = {k: v for k, v in os.environ.items() if not k.startswith("T_")}
        env.update({
            "T_DATE": "20260702", "T_POLICY_NUMBER": "POL000000001",
            "T_TYPE": "LCH", "T_ADJUST": "150.00", "T_USERID": userid,
        })
        env.update({f"T_{k.upper()}": str(v) for k, v in overrides.items()})
        result = subprocess.run([str(self.binary)], env=env, text=True,
                                capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("RETURN", result.stdout)
        return result.stdout

    def test_endorsement_row_records_signed_on_user(self):
        out = self.run_polend()
        self.assertIn("INSERT PROCESSED_BY=[MALLORY ]", out)

    def test_premium_update_records_signed_on_user(self):
        out = self.run_polend()
        self.assertIn("UPDATE UPDATED_BY=[MALLORY ] PREM= 000001150.00", out)

    def test_audit_queue_record_carries_user_terminal_and_change(self):
        out = self.run_polend()
        self.assertIn("CONFIRM", out)
        self.assertNotIn("ROLLBACK", out)
        rec = re.search(r"AUDIT REC=\[(.*)\]", out, re.S).group(1)
        # Legacy prefix keeps POLICY-RECORD; actor fields follow it
        self.assertTrue(rec.startswith("POL000000001AUTAC"), rec)
        self.assertEqual(rec[89:97], "20260702")        # LAST-UPDATED
        self.assertEqual(rec[97:105], "MALLORY ")       # UPDATED-BY
        self.assertEqual(rec[125:133], "MALLORY ")      # AUD-USERID
        self.assertEqual(rec[133:137], "T042")          # AUD-TERMID
        self.assertEqual(rec[137:141], "PEND")          # AUD-TRANSID
        self.assertEqual(rec[141:149], "POLEND  ")      # AUD-PROGRAM-ID
        self.assertEqual(rec[149:157], "00007LCH")      # SEQ + TYPE

    def test_audit_prefix_matches_policy_record_length(self):
        out = self.run_polend()
        self.assertIn("AUDIT POLICY-LEN=125", out)

    def test_different_users_are_distinguished(self):
        out = self.run_polend(userid="AUDTEST1")
        self.assertIn("INSERT PROCESSED_BY=[AUDTEST1]", out)
        self.assertIn("UPDATE UPDATED_BY=[AUDTEST1]", out)

    def test_unidentified_user_is_rejected_before_any_write(self):
        for overrides in ({"userid": " "}, {"assign_resp": "16"}):
            out = self.run_polend(**overrides)
            self.assertIn("ERROR=UNABLE TO IDENTIFY SIGNED-ON USER", out)
            self.assertNotIn("INSERT PROCESSED_BY", out)
            self.assertNotIn("UPDATE UPDATED_BY", out)
            self.assertNotIn("AUDIT", out)
            self.assertNotIn("CONFIRM", out)

    def test_insert_failure_skips_premium_update_and_audit(self):
        out = self.run_polend(insert_sqlcode="-803")
        self.assertIn("INSERT PROCESSED_BY", out)
        self.assertNotIn("UPDATE UPDATED_BY", out)
        self.assertNotIn("AUDIT", out)
        self.assertIn("ROLLBACK", out)
        self.assertIn("ERROR=DB2 ERROR INSERTING ENDORSEMENT", out)
        self.assertNotIn("CONFIRM", out)

    def test_sequence_failure_writes_nothing(self):
        out = self.run_polend(seq_sqlcode="-904")
        self.assertNotIn("INSERT PROCESSED_BY", out)
        self.assertNotIn("AUDIT", out)
        self.assertIn("ERROR=DB2 ERROR READING ENDORSEMENT SEQUENCE", out)

    def test_update_failure_rolls_back_endorsement(self):
        out = self.run_polend(update_sqlcode="-911")
        self.assertIn("UPDATE UPDATED_BY", out)
        self.assertNotIn("AUDIT", out)
        self.assertIn("ROLLBACK", out)
        self.assertIn("ERROR=DB2 ERROR UPDATING POLICY PREMIUM", out)

    def test_audit_write_failure_rolls_back_change(self):
        out = self.run_polend(writeq_resp="16")
        self.assertIn("ROLLBACK", out)
        self.assertIn("ERROR=ERROR WRITING ENDORSEMENT AUDIT TRAIL", out)
        self.assertNotIn("CONFIRM", out)


if __name__ == "__main__":
    unittest.main()
