"""Static regression checks for UNDWRT (PUWR) operator authorization."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROGRAM = ROOT / "cobol" / "programs" / "UNDWRT.cbl"
COPYBOOK = ROOT / "cobol" / "copybooks" / "POLICY-RECORD.cpy"


def paragraphs(source):
    """Map paragraph name -> upper-cased, whitespace-collapsed body."""
    body = source.split("PROCEDURE DIVISION.", 1)[1]
    result, name, lines = {}, None, []
    for line in body.splitlines():
        if len(line) > 6 and line[6] == "*":
            continue
        text = line[7:72] if len(line) > 7 else ""
        head = re.fullmatch(r"([0-9A-Z][0-9A-Z-]*)\.", text.strip())
        if head and not text.startswith("    "):
            if name:
                result[name] = " ".join(" ".join(lines).upper().split())
            name, lines = head.group(1), []
        elif name:
            lines.append(text)
    if name:
        result[name] = " ".join(" ".join(lines).upper().split())
    return result


class UndwrtAuthorizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.src = PROGRAM.read_text()
        cls.p = paragraphs(cls.src)

    def test_source_within_column_72(self):
        long_lines = [n for n, l in enumerate(self.src.splitlines(), 1)
                      if len(l) > 72]
        self.assertEqual(long_lines, [])

    def test_operator_identity_fails_closed(self):
        init = self.p["1000-INITIALIZE"]
        self.assertIn("EXEC CICS ASSIGN USERID(WS-OPERATOR-ID)", init)
        self.assertIn("OR WS-OPERATOR-ID = SPACES", init)

    def test_no_hardcoded_actor(self):
        write = self.p["6000-WRITE-DECISION"]
        self.assertNotIn("'SYSTEM'", write)
        self.assertNotIn("'UNDWRT'", write)
        self.assertIn("UPDATED_BY = :WS-OPERATOR-ID", write)
        insert = write[write.index("INSERT INTO UNDERWRITING_DECISIONS"):]
        self.assertIn(":WS-OPERATOR-ID", insert)
        self.assertIn(":WS-OVERRIDE-BY :WS-IND-OVERRIDE-BY", insert)

    def test_branch_authority_checked(self):
        auth = self.p["2500-AUTHORIZE-BRANCH"]
        self.assertIn("IF POLICY-BRANCH-CODE = SPACES", auth)
        self.assertIn("RESID(WS-UW-DECIDE-RESID)", auth)
        self.assertIn("NOT = DFHVALUE(UPDATABLE)", auth)
        self.assertIn("'ACME.PAS.UW.DECIDE.'", self.src)

    def test_final_or_overridden_decision_needs_override_role(self):
        prior = self.p["2600-CHECK-PRIOR-DECISION"]
        self.assertIn("FROM UNDERWRITING_DECISIONS", prior)
        self.assertIn("PRIOR-UW-FINAL OR (WS-PRIOR-OVERRIDE-BY NOT = SPACES"
                      " AND WS-PRIOR-DECISION-CODE = WS-PRIOR-UW-STATUS)",
                      prior)
        self.assertIn(":WS-PRIOR-DECISION-TS", prior)
        self.assertIn("RESID(WS-UW-OVERRIDE-RESID)", prior)
        self.assertRegex(self.src, r"88\s+PRIOR-UW-FINAL\s+VALUE 'AP' 'DC'")

    def test_checks_gate_scoring_and_writes(self):
        main = self.p["0000-MAIN-LOGIC"]
        order = ["PERFORM 1000-INITIALIZE",
                 "PERFORM 2000-READ-POLICY-DATA THRU 2000-EXIT",
                 "PERFORM 2500-AUTHORIZE-BRANCH",
                 "PERFORM 2600-CHECK-PRIOR-DECISION",
                 "PERFORM 6000-WRITE-DECISION",
                 "PERFORM 7000-SEND-MQ-NOTIFICATION"]
        positions = [main.index(step) for step in order]
        self.assertEqual(positions, sorted(positions))
        for a, b in zip(positions, positions[1:]):
            self.assertIn("IF WS-ERROR-MSG = SPACES", main[a:b])

    def test_map_supplies_policy_number_only(self):
        read = self.p["2000-READ-POLICY-DATA"]
        self.assertLess(read.index("EXEC CICS RECEIVE"),
                        read.index("INITIALIZE POLICY-RECORD"))
        self.assertLess(read.index("INITIALIZE POLICY-RECORD"),
                        read.index("EXEC SQL SELECT"))

    def test_dates_read_as_iso_char(self):
        read = self.p["2000-READ-POLICY-DATA"]
        sql = read[read.index("EXEC SQL SELECT"):read.index("END-EXEC",
                                                       read.index("EXEC SQL SELECT"))]
        for col in ("EFFECTIVE_DATE", "EXPIRY_DATE", "INCEPTION_DATE"):
            self.assertIn(f"CHAR(P.{col}, ISO)", sql)
        for field in ("EFFECTIVE", "EXPIRY", "INCEPTION"):
            self.assertNotIn(f":POLICY-{field}-DATE", sql)
            self.assertIn(f"TO POLICY-{field}-DATE", read)

    def test_write_is_atomic_and_concurrency_safe(self):
        write = self.p["6000-WRITE-DECISION"]
        self.assertIn("COALESCE(UW_STATUS, ' ') = :WS-PRIOR-UW-STATUS",
                      write)
        self.assertIn("AND BRANCH_CODE = :POLICY-BRANCH-CODE", write)
        self.assertIn("AND NOT EXISTS (SELECT 1 FROM UNDERWRITING_DECISIONS",
                      write)
        self.assertIn("TIMESTAMP(:WS-PRIOR-DECISION-TS)", write)
        self.assertIn("IF SQLCODE NOT = 0 OR SQLERRD(3) NOT = 1", write)
        self.assertIn("SQLCODE = -803", write)
        self.assertIn("EXEC CICS SYNCPOINT ROLLBACK", write)

    def test_referral_codes_recognised_by_copybook(self):
        cpy = COPYBOOK.read_text()
        self.assertRegex(cpy, r"88\s+UW-REFERRED\s+VALUE 'RF' 'RS' 'RM'\.")
        for code in re.findall(r"88\s+DEC-\S+\s+VALUE '(\w\w)'", self.src):
            self.assertIn(f"'{code}'", cpy)


if __name__ == "__main__":
    unittest.main()
