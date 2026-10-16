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


def stub_exec_block(match):
    block = " ".join(match.group(0).upper().split())
    if "INCLUDE SQLCA" in block:
        return """       01  SQLCODE PIC S9(9) COMP.
       01  WS-TEST-POLICY-NUMBER PIC X(12).
       01  WS-TEST-POLICY-STATUS PIC X(02).
       01  WS-TEST-EFFECTIVE-DATE PIC 9(08).
       01  WS-TEST-EXPIRY-DATE PIC 9(08).
       01  WS-TEST-PREMIUM-TEXT PIC X(16).
       01  WS-DISPLAY-ADJUST PIC +9(9).99.
       01  WS-DISPLAY-PREMIUM PIC +9(9).99."""
    if "SELECT POLICY_NUMBER, POLICY_STATUS" in block:
        return """           ACCEPT WS-TEST-POLICY-NUMBER
               FROM ENVIRONMENT 'T_POLICY_NUMBER'
           ACCEPT WS-TEST-POLICY-STATUS
               FROM ENVIRONMENT 'T_STATUS'
           ACCEPT WS-TEST-EFFECTIVE-DATE
               FROM ENVIRONMENT 'T_EFFECTIVE_DATE'
           ACCEPT WS-TEST-EXPIRY-DATE
               FROM ENVIRONMENT 'T_EXPIRY_DATE'
           ACCEPT WS-TEST-PREMIUM-TEXT
               FROM ENVIRONMENT 'T_PREMIUM'
           MOVE WS-TEST-POLICY-NUMBER TO POLICY-NUMBER
           MOVE WS-TEST-POLICY-STATUS TO POLICY-STATUS
           MOVE WS-TEST-EFFECTIVE-DATE TO POLICY-EFFECTIVE-DATE
           MOVE WS-TEST-EXPIRY-DATE TO POLICY-EXPIRY-DATE
           COMPUTE POLICY-TOTAL-PREMIUM =
               FUNCTION NUMVAL(WS-TEST-PREMIUM-TEXT)
           MOVE 0 TO SQLCODE"""
    if "MAX(ENDORSEMENT_SEQ)" in block:
        return """           MOVE 1 TO WS-ENDORSEMENT-SEQ
           MOVE 0 TO SQLCODE"""
    if "INSERT INTO ENDORSEMENTS" in block:
        return """           MOVE WS-PREMIUM-ADJUST TO WS-DISPLAY-ADJUST
           DISPLAY 'INSERT ADJ=' WS-DISPLAY-ADJUST
           MOVE 0 TO SQLCODE"""
    if "UPDATE POLICIES" in block:
        return """           MOVE POLICY-TOTAL-PREMIUM TO WS-DISPLAY-PREMIUM
           DISPLAY 'UPDATE PREM=' WS-DISPLAY-PREMIUM
               ' STATUS=' POLICY-STATUS
           MOVE 0 TO SQLCODE"""
    if "SYNCPOINT ROLLBACK" in block:
        return "           DISPLAY 'ROLLBACK'"
    if "CICS ASKTIME" in block or "CICS FORMATTIME" in block:
        return """           ACCEPT WS-CURRENT-DATE
               FROM ENVIRONMENT 'T_DATE'"""
    if "CICS RECEIVE" in block:
        return """           MOVE 0 TO WS-RESPONSE-CODE
           ACCEPT WS-ENDORSEMENT-TYPE
               FROM ENVIRONMENT 'T_TYPE'"""
    if "CICS WRITEQ TS" in block:
        return "           CONTINUE"
    if "CICS SEND TEXT" in block:
        return "           DISPLAY 'ERROR=' WS-ERROR-MSG"
    if "CICS SEND" in block:
        return "           DISPLAY 'CONFIRM'"
    if "CICS RETURN" in block:
        return "           STOP RUN."
    raise AssertionError(f"No COBOL test stub for EXEC block: {match.group(0)}")


def make_test_source(source):
    exec_pattern = re.compile(
        r"^ {7,}EXEC\b.*?\bEND-EXEC\b\.?",
        re.MULTILINE | re.DOTALL,
    )
    source, replaced_blocks = exec_pattern.subn(stub_exec_block, source)
    if replaced_blocks == 0:
        raise AssertionError("POLEND source contains no EXEC blocks to stub")
    return re.sub(r"DFHRESP\(NORMAL\)", "0", source, flags=re.IGNORECASE)


class PolendPremiumTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cobc = shutil.which("cobc")
        if cobc is None:
            message = "GnuCOBOL (cobc) is not installed"
            if "CI" in os.environ:
                raise RuntimeError(f"{message}; CI requires the COBOL tests to run")
            raise unittest.SkipTest(f"{message}; skipping POLEND COBOL harness")

        source_path = Path(os.environ.get("POLEND_SRC", POLEND_PATH))
        source = source_path.read_text()
        cls.temp_dir = tempfile.TemporaryDirectory(prefix="polend-cobol-")
        cls.binary_path = Path(cls.temp_dir.name) / "polend-test"
        transformed_path = Path(cls.temp_dir.name) / "POLEND.cbl"
        transformed_path.write_text(make_test_source(source))
        compile_result = subprocess.run(
            [
                cobc,
                "-x",
                "-I",
                str(COPYBOOK_PATH),
                "-o",
                str(cls.binary_path),
                str(transformed_path),
            ],
            text=True,
            capture_output=True,
        )
        if compile_result.returncode != 0:
            raise RuntimeError(
                "Failed to compile POLEND test harness:\n"
                f"{compile_result.stdout}{compile_result.stderr}"
            )

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "temp_dir"):
            cls.temp_dir.cleanup()

    def run_polend(
        self,
        endorsement_type,
        endorsement_date,
        premium="1000.00",
        status="AC",
    ):
        environment = os.environ.copy()
        environment.update(
            {
                "T_DATE": endorsement_date,
                "T_TYPE": endorsement_type,
                "T_POLICY_NUMBER": "POLICY000001",
                "T_STATUS": status,
                "T_EFFECTIVE_DATE": "20260101",
                "T_EXPIRY_DATE": "20270101",
                "T_PREMIUM": premium,
            }
        )
        result = subprocess.run(
            [str(self.binary_path)],
            env=environment,
            text=True,
            capture_output=True,
        )
        self.assertEqual(
            result.returncode,
            0,
            f"POLEND harness failed:\n{result.stdout}{result.stderr}",
        )
        return result.stdout

    @staticmethod
    def amount_from(output, label):
        match = re.search(
            rf"{re.escape(label)}\s*([+-]\d+\.\d{{2}})",
            output,
        )
        if match is None:
            raise AssertionError(f"{label} amount not found in output:\n{output}")
        return Decimal(match.group(1))

    @staticmethod
    def status_from(output):
        match = re.search(r"UPDATE PREM=[^\n]*\bSTATUS=([A-Z]{2})", output)
        if match is None:
            raise AssertionError(f"UPDATE status not found in output:\n{output}")
        return match.group(1)

    def test_cancel_books_pro_rata_return_premium(self):
        output = self.run_polend("CAN", "20260702")
        self.assertEqual(self.amount_from(output, "INSERT ADJ="), Decimal("-501.37"))
        self.assertEqual(self.amount_from(output, "UPDATE PREM="), Decimal("498.63"))
        self.assertEqual(self.status_from(output), "CN")

    def test_address_change_does_not_change_premium(self):
        output = self.run_polend("ACH", "20260702")
        self.assertEqual(self.amount_from(output, "INSERT ADJ="), Decimal("0.00"))
        self.assertEqual(self.amount_from(output, "UPDATE PREM="), Decimal("1000.00"))
        self.assertEqual(self.status_from(output), "AC")

    def test_cancel_large_premium_near_expiry(self):
        output = self.run_polend("CAN", "20261231", premium="1000000.00")
        self.assertEqual(self.amount_from(output, "INSERT ADJ="), Decimal("-2739.73"))
        self.assertEqual(self.amount_from(output, "UPDATE PREM="), Decimal("997260.27"))

    def test_repeat_cancellation_is_rejected(self):
        first_output = self.run_polend("CAN", "20260702")
        status = self.status_from(first_output)
        second_output = self.run_polend(
            "CAN",
            "20260702",
            premium="498.63",
            status=status,
        )
        self.assertIn("ONLY ACTIVE POLICIES CAN BE ENDORSED", second_output)
        self.assertNotIn("INSERT ADJ=", second_output)
        self.assertNotIn("UPDATE PREM=", second_output)

    def test_date_after_expiry_does_not_write(self):
        output = self.run_polend("CAN", "20270301")
        self.assertIn("OUTSIDE POLICY TERM", output)
        self.assertNotIn("INSERT ADJ=", output)
        self.assertNotIn("UPDATE PREM=", output)

    def test_unknown_endorsement_type_does_not_write(self):
        output = self.run_polend("XYZ", "20260702")
        self.assertIn("INVALID ENDORSEMENT TYPE", output)
        self.assertNotIn("INSERT ADJ=", output)
        self.assertNotIn("UPDATE PREM=", output)


if __name__ == "__main__":
    unittest.main()
