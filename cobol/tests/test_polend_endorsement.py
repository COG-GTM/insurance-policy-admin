"""POLEND regression tests: EXEC CICS/SQL blocks are replaced with stubs
driven by environment variables and the program is run under GnuCOBOL."""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
POLEND_PATH = REPO_ROOT / "cobol" / "programs" / "POLEND.cbl"
COPYBOOK_PATH = REPO_ROOT / "cobol" / "copybooks"

TEST_FIELDS = """       01  SQLCODE PIC S9(9) COMP.
       01  T-TEXT PIC X(20).
       01  T-SQLCODE PIC S9(9).
       01  T-SHOW-AMT PIC -9(9).99.
       01  T-SHOW-PREM PIC -9(9).99"""


def accept(target, var):
    return (f"           ACCEPT {target}\n"
            f"               FROM ENVIRONMENT '{var}'")


def set_sqlcode(var):
    return f"""           MOVE SPACES TO T-TEXT
{accept('T-TEXT', var)}
           IF T-TEXT = SPACES
               MOVE 0 TO SQLCODE
           ELSE
               COMPUTE SQLCODE = FUNCTION NUMVAL(T-TEXT)
           END-IF"""


def stub(match):
    block = " ".join(match.group(0).upper().split())
    if "INCLUDE SQLCA" in block:
        return TEST_FIELDS
    if "CICS ASKTIME" in block:
        return accept("WS-CURRENT-DATE", "T_DATE")
    if "CICS FORMATTIME" in block:
        return "           CONTINUE"
    if "CICS RECEIVE" in block:
        if "INTO(WS-ENDORSEMENT-INPUT)" in block.replace(" ", ""):
            return "\n".join([
                "           MOVE 0 TO WS-RESPONSE-CODE",
                accept("EI-POLICY-NUMBER", "T_POLICY_NUMBER"),
                accept("EI-ENDORSEMENT-TYPE", "T_TYPE"),
                accept("EI-ADJUST-SIGN", "T_SIGN"),
                accept("T-TEXT", "T_AMOUNT"),
                "           MOVE T-TEXT(1:9) TO WS-ENDORSEMENT-INPUT(17:9)",
            ])
        # pre-fix layout: map read into POLICY-RECORD only
        return "\n".join([
            "           MOVE 0 TO WS-RESPONSE-CODE",
            accept("POLICY-NUMBER", "T_POLICY_NUMBER"),
        ])
    if "SELECT POLICY_NUMBER, POLICY_STATUS" in block:
        return "\n".join([
            accept("POLICY-STATUS", "T_STATUS"),
            accept("POLICY-EFFECTIVE-DATE", "T_EFFECTIVE_DATE"),
            accept("POLICY-EXPIRY-DATE", "T_EXPIRY_DATE"),
            accept("T-TEXT", "T_PREMIUM"),
            "           COMPUTE POLICY-TOTAL-PREMIUM =",
            "               FUNCTION NUMVAL(T-TEXT)",
            "           MOVE 0 TO SQLCODE",
        ])
    if "MAX(ENDORSEMENT_SEQ)" in block:
        return "           MOVE 1 TO WS-ENDORSEMENT-SEQ\n" + set_sqlcode(
            "T_SEQ_SQLCODE")
    if "INSERT INTO ENDORSEMENTS" in block:
        return set_sqlcode("T_INSERT_SQLCODE") + """
           MOVE WS-PREMIUM-ADJUST TO T-SHOW-AMT
           DISPLAY 'INSERT TYPE=' WS-ENDORSEMENT-TYPE
               ' ADJ=' T-SHOW-AMT ' SQLCODE=' SQLCODE"""
    if "UPDATE POLICIES" in block:
        return set_sqlcode("T_UPDATE_SQLCODE") + """
           MOVE POLICY-TOTAL-PREMIUM TO T-SHOW-PREM
           DISPLAY 'UPDATE PREM=' T-SHOW-PREM
               ' STATUS=' POLICY-STATUS ' SQLCODE=' SQLCODE"""
    if "SYNCPOINT ROLLBACK" in block:
        return "           DISPLAY 'ROLLBACK'"
    if "CICS WRITEQ TS" in block:
        return "           DISPLAY 'AUDIT'"
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


class PolendEndorsementTests(unittest.TestCase):
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

    def run_polend(self, type_="CAD", sign="+", amount="000036500",
                   date="20260702", status="AC", premium="1000.00", **sql):
        env = {k: v for k, v in os.environ.items() if not k.startswith("T_")}
        env.update({
            "T_DATE": date, "T_POLICY_NUMBER": "POL000000001",
            "T_TYPE": type_, "T_SIGN": sign, "T_AMOUNT": amount,
            "T_STATUS": status, "T_EFFECTIVE_DATE": "20260101",
            "T_EXPIRY_DATE": "20270101", "T_PREMIUM": premium,
        })
        env.update({f"T_{k.upper()}": str(v) for k, v in sql.items()})
        result = subprocess.run([str(self.binary)], env=env, text=True,
                                capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("RETURN", result.stdout)
        return result.stdout

    @staticmethod
    def amount(output, label):
        m = re.search(rf"{label}=\s*(-?\d+\.\d\d)", output)
        if not m:
            raise AssertionError(f"{label} not found in:\n{output}")
        return Decimal(m.group(1))

    def assert_rejected(self, output, message):
        self.assertIn(f"ERROR={message}", output)
        self.assertNotIn("INSERT TYPE=", output)
        self.assertNotIn("UPDATE PREM=", output)
        self.assertNotIn("CONFIRM", output)

    # 183 of 365 days remain on 2026-07-02 -> factor 0.501370
    def test_coverage_add_books_pro_rata_amount_from_map(self):
        out = self.run_polend("CAD", "+", "000036500")
        self.assertIn("INSERT TYPE=CAD", out)
        self.assertEqual(self.amount(out, "ADJ"), Decimal("183.00"))
        self.assertEqual(self.amount(out, "PREM"), Decimal("1183.00"))
        self.assertIn("STATUS=AC", out)
        self.assertIn("AUDIT", out)
        self.assertIn("CONFIRM", out)
        self.assertNotIn("ROLLBACK", out)

    def test_coverage_removal_reduces_premium(self):
        out = self.run_polend("CRM", "-", "000020000")
        self.assertEqual(self.amount(out, "ADJ"), Decimal("-100.27"))
        self.assertEqual(self.amount(out, "PREM"), Decimal("899.73"))

    def test_address_change_books_zero(self):
        out = self.run_polend("ACH", "+", "000000000")
        self.assertEqual(self.amount(out, "ADJ"), Decimal("0.00"))
        self.assertEqual(self.amount(out, "PREM"), Decimal("1000.00"))

    def test_cancel_returns_unearned_premium(self):
        out = self.run_polend("CAN", "+", "000000000")
        self.assertEqual(self.amount(out, "ADJ"), Decimal("-501.37"))
        self.assertEqual(self.amount(out, "PREM"), Decimal("498.63"))
        self.assertIn("STATUS=CN", out)

    def test_insert_failure_rolls_back_and_skips_update(self):
        out = self.run_polend(insert_sqlcode="-551")
        self.assertIn("INSERT TYPE=CAD", out)
        self.assertIn("ROLLBACK", out)
        self.assertNotIn("UPDATE PREM=", out)
        self.assertNotIn("AUDIT", out)
        self.assertNotIn("CONFIRM", out)
        self.assertIn("ERROR=DB2 ERROR INSERTING ENDORSEMENT", out)

    def test_update_failure_rolls_back(self):
        out = self.run_polend(update_sqlcode="-911")
        self.assertIn("UPDATE PREM=", out)
        self.assertIn("ROLLBACK", out)
        self.assertNotIn("CONFIRM", out)
        self.assertIn("ERROR=DB2 ERROR UPDATING POLICY PREMIUM", out)

    def test_sequence_failure_writes_nothing(self):
        out = self.run_polend(seq_sqlcode="-904")
        self.assert_rejected(out, "DB2 ERROR READING ENDORSEMENT SEQUENCE")
        self.assertIn("ROLLBACK", out)

    def test_missing_type_rejected(self):
        self.assert_rejected(self.run_polend(type_="   "),
                             "ENDORSEMENT TYPE IS REQUIRED")

    def test_unknown_type_rejected(self):
        self.assert_rejected(self.run_polend(type_="XYZ"),
                             "INVALID ENDORSEMENT TYPE")

    def test_non_numeric_amount_rejected(self):
        self.assert_rejected(self.run_polend(amount="00001A000"),
                             "PREMIUM CHANGE MUST BE SIGNED AND NUMERIC")

    def test_missing_sign_rejected(self):
        self.assert_rejected(self.run_polend(sign=" "),
                             "PREMIUM CHANGE MUST BE SIGNED AND NUMERIC")

    def test_sign_must_match_type(self):
        self.assert_rejected(self.run_polend("CAD", "-", "000010000"),
                             "COVERAGE ADD NEEDS A POSITIVE PREMIUM")
        self.assert_rejected(self.run_polend("CRM", "+", "000010000"),
                             "COVERAGE REMOVAL NEEDS A NEGATIVE PREMIUM")
        self.assert_rejected(self.run_polend("LCH", "+", "000000000"),
                             "LIMIT CHANGE NEEDS A PREMIUM CHANGE")
        self.assert_rejected(self.run_polend("ACH", "+", "000000100"),
                             "NO PREMIUM CHANGE ALLOWED FOR THIS TYPE")

    def test_reduction_below_zero_rejected(self):
        self.assert_rejected(self.run_polend("CRM", "-", "000500000"),
                             "PREMIUM ADJUSTMENT OUT OF RANGE")

    def test_date_outside_term_rejected(self):
        self.assert_rejected(self.run_polend(date="20270301"),
                             "ENDORSEMENT DATE OUTSIDE POLICY TERM")
        self.assert_rejected(self.run_polend(date="20251231"),
                             "ENDORSEMENT DATE OUTSIDE POLICY TERM")

    def test_inactive_policy_rejected(self):
        self.assert_rejected(self.run_polend(status="CN"),
                             "ONLY ACTIVE POLICIES CAN BE ENDORSED")


if __name__ == "__main__":
    unittest.main()
