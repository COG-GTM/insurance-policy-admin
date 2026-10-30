"""Runs UNDWRT's scoring/decision paragraphs under GnuCOBOL with CICS/DB2
stubbed out, writes POLICY-RECORD the way 7000-SEND-MQ-NOTIFICATION queues
it, and decodes the queued bytes with the compat layout parser.

Needs `cobc` (GnuCOBOL 3.x); skipped otherwise. Override the sources with
UNDWRT_SOURCE / COPYBOOK_DIR to run against another revision."""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "compat"))
from check_layouts import parse_copybook  # noqa: E402

SOURCE = Path(os.environ.get(
    "UNDWRT_SOURCE", ROOT / "cobol" / "programs" / "UNDWRT.cbl"))
COPYBOOKS = Path(os.environ.get(
    "COPYBOOK_DIR", ROOT / "cobol" / "copybooks"))

INPUTS = [
    ("UW_CREDIT", "CUST-CREDIT-SCORE"),
    ("UW_CLAIMS", "WS-CLAIM-COUNT"),
    ("UW_INCURRED", "WS-TOTAL-INCURRED"),
    ("UW_PREMIUM", "POLICY-TOTAL-PREMIUM"),
    ("UW_LIMIT", "POLICY-LIMIT"),
    ("UW_ACCUM", "WS-ACCUM-LIMIT"),
]


def build_harness(source, record_length):
    text = re.sub(r"EXEC\s+SQL\s+INCLUDE\s+SQLCA\s+END-EXEC\.",
                  "01  SQLCODE PIC S9(9) COMP.", source)
    text = re.sub(
        r"EXEC\s+CICS\s+WRITEQ\s+TD.*?END-EXEC",
        "OPEN OUTPUT TDQ-FILE\n           WRITE TDQ-REC FROM POLICY-RECORD"
        "\n           CLOSE TDQ-FILE", text, flags=re.S)
    text = re.sub(r"EXEC\s+(CICS|SQL)\b.*?END-EXEC", "CONTINUE", text,
                  flags=re.S)
    text = text.replace("DFHRESP(NORMAL)", "0")
    text = text.replace(
        "       ENVIRONMENT DIVISION.\n",
        "       ENVIRONMENT DIVISION.\n"
        "       INPUT-OUTPUT SECTION.\n"
        "       FILE-CONTROL.\n"
        "           SELECT TDQ-FILE ASSIGN TO 'TDQOUT'\n"
        "               ORGANIZATION IS SEQUENTIAL.\n")
    text = text.replace(
        "       WORKING-STORAGE SECTION.\n",
        "       FILE SECTION.\n"
        "       FD  TDQ-FILE.\n"
        "       01  TDQ-REC PIC X(%d).\n"
        "       WORKING-STORAGE SECTION.\n"
        "       01  WS-T-IN PIC X(20).\n" % record_length)
    driver = ["       PROCEDURE DIVISION.", "       T000-DRIVER."]
    for env, field in INPUTS:
        driver += [
            "           ACCEPT WS-T-IN FROM ENVIRONMENT '%s'" % env,
            "           MOVE FUNCTION NUMVAL(WS-T-IN) TO %s" % field,
        ]
    driver += [
        "           ACCEPT CUST-RISK-TIER FROM ENVIRONMENT 'UW_TIER'",
        "           MOVE 'B001' TO POLICY-BRANCH-CODE",
        "           MOVE 0 TO WS-RISK-SCORE",
        "           PERFORM 3000-CALCULATE-RISK-SCORE",
        "           PERFORM 4000-CHECK-ACCUMULATION",
        "           PERFORM 5000-RENDER-DECISION",
        "           PERFORM 7000-SEND-MQ-NOTIFICATION",
        "           DISPLAY 'WS-RISK-SCORE=' WS-RISK-SCORE",
        "           DISPLAY 'WS-DECISION=' WS-DECISION",
        "           STOP RUN.",
    ]
    return text.replace("       PROCEDURE DIVISION.\n",
                        "\n".join(driver) + "\n", 1)


@unittest.skipUnless(shutil.which("cobc"), "GnuCOBOL (cobc) not installed")
class UnderwritingRiskScoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        layout = (COPYBOOKS / "POLICY-RECORD.cpy").read_text()
        cls.fields, cls.length = parse_copybook(layout)
        cls.workdir = tempfile.mkdtemp(prefix="undwrt-")
        src = Path(cls.workdir, "UWHARN.cbl")
        src.write_text(build_harness(SOURCE.read_text(), cls.length))
        cls.binary = Path(cls.workdir, "uwharn")
        subprocess.run(
            ["cobc", "-x", "-fixed", "-I",
             str(COPYBOOKS), "-o", str(cls.binary), str(src)],
            check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.workdir, ignore_errors=True)

    def run_policy(self, credit=0, tier="S", claims=0, incurred=0,
                   premium=1000, limit=1000000, accum=0):
        env = dict(os.environ, UW_CREDIT=str(credit), UW_TIER=tier,
                   UW_CLAIMS=str(claims), UW_INCURRED=str(incurred),
                   UW_PREMIUM=str(premium), UW_LIMIT=str(limit),
                   UW_ACCUM=str(accum))
        out = subprocess.run([str(self.binary)], cwd=self.workdir, env=env,
                             check=True, capture_output=True,
                             text=True).stdout
        values = dict(line.split("=", 1) for line in out.splitlines()
                      if "=" in line)
        record = Path(self.workdir, "TDQOUT").read_bytes()[:self.length]
        queued = {}
        for name in ("POLICY-RISK-SCORE", "POLICY-RISK-SCORE-FULL",
                     "POLICY-UW-STATUS"):
            if name in self.fields:
                off, size, _ = self.fields[name]
                queued[name] = record[off:off + size].decode("ascii")
        return values, queued

    def test_1050_score_is_queued_in_full(self):
        # 400 (no credit score) + 200 substandard + 150 + 200 claims
        # + 100 limit over 5M
        values, queued = self.run_policy(credit=0, tier="U", claims=6,
                                         limit=6000000)
        self.assertEqual(values["WS-DECISION"], "DC")
        self.assertEqual(queued["POLICY-RISK-SCORE-FULL"], "1050")
        self.assertEqual(queued["POLICY-RISK-SCORE"], "999")
        self.assertEqual(queued["POLICY-UW-STATUS"], "DC")

    def test_finding_example_1299(self):
        # 449 credit base + 200 + 350 claims + 200 loss ratio + 100 limit
        values, queued = self.run_policy(credit=2, tier="U", claims=6,
                                         incurred=850, premium=1000,
                                         limit=6000000)
        self.assertEqual(queued["POLICY-RISK-SCORE-FULL"], "1299")
        self.assertEqual(queued["POLICY-RISK-SCORE"], "999")
        self.assertEqual(values["WS-DECISION"], "DC")

    def test_scores_below_1000_unchanged(self):
        values, queued = self.run_policy(credit=700, tier="U", claims=4)
        self.assertEqual(queued["POLICY-RISK-SCORE-FULL"], "0450")
        self.assertEqual(queued["POLICY-RISK-SCORE"], "450")
        self.assertEqual(values["WS-DECISION"], "RS")

    def test_negative_intermediate_floors_at_zero(self):
        # (900 - 850) / 2 = 25, preferred -100 -> floored at 0, not 75
        values, queued = self.run_policy(credit=850, tier="P")
        self.assertEqual(queued["POLICY-RISK-SCORE-FULL"], "0000")
        self.assertEqual(values["WS-DECISION"], "AP")

    def test_credit_above_900_floors_at_zero(self):
        values, queued = self.run_policy(credit=950, tier="S")
        self.assertEqual(queued["POLICY-RISK-SCORE-FULL"], "0000")


if __name__ == "__main__":
    unittest.main()
