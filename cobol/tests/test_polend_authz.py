"""Static structural regression tests for POLEND.cbl authorization.

No CICS/DB2 runtime is available in CI, so these tests parse the fixed-format
COBOL source and assert the security-relevant structure of the program:
the servicing branch must come from DB2 (not the BMS map), validation must
call the authorization paragraphs, and QUERY SECURITY must fail closed.
"""
import os
import re
import unittest

COBOL_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "programs", "POLEND.cbl"
)


def load_paragraphs(path):
    """Parse fixed-format COBOL into {paragraph_name: text}.

    A paragraph header is a line whose area A (columns 8+, i.e. index 7)
    matches 'NAME.' alone. Columns 1-6 (sequence numbers) are stripped and
    comment lines ('*' in column 7) are dropped.
    """
    paragraphs = {}
    current = None
    chunks = []
    header_re = re.compile(r"^([0-9A-Z-]+)\.\s*$")
    with open(path, encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if len(line) < 8:
                continue
            if line[6:7] == "*":  # comment in column 7
                continue
            body = line[7:72] if len(line) > 7 else ""
            if line[7:8] == "*":  # comment starting in column 8
                continue
            stripped = body.strip()
            if not stripped:
                continue
            m = header_re.match(stripped)
            if m and not body.startswith(" "):
                if current is not None:
                    paragraphs[current] = " ".join(chunks)
                current = m.group(1)
                chunks = []
            elif current is not None:
                chunks.append(stripped)
    if current is not None:
        paragraphs[current] = " ".join(chunks)
    return paragraphs


def norm(text):
    return re.sub(r"\s+", " ", text)


class PolendAuthzTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paragraphs = load_paragraphs(COBOL_PATH)

    def para(self, name):
        self.assertIn(name, self.paragraphs, f"paragraph {name} missing")
        return norm(self.paragraphs[name])

    def test_policy_branch_read_from_db2_not_map(self):
        text = self.para("2000-RECEIVE-ENDORSEMENT")
        m = re.search(r"SELECT (.*?) FROM POLICIES", text)
        self.assertIsNotNone(m, "no SELECT ... FROM POLICIES found")
        select = m.group(0)
        self.assertIn("BRANCH_CODE", select)
        self.assertRegex(select, r":POLICY-BRANCH-CODE\s+:WS-BRANCH-IND")

    def test_validation_performs_authorization(self):
        text = self.para("3000-VALIDATE-ENDORSEMENT")
        self.assertIn("PERFORM 3100-AUTHORIZE-ENDORSEMENT", text)

    def test_branch_authorization_fails_closed(self):
        text = self.para("3100-AUTHORIZE-ENDORSEMENT")
        self.assertIn("'PAS.POLEND.BRANCH.'", text)
        self.assertIn("POLICY-BRANCH-CODE", text)
        self.assertRegex(text, r"WS-BRANCH-IND\s*<\s*0")
        self.assertIn("IF NOT WS-SEC-ACCESS-GRANTED", text)
        self.assertRegex(
            text,
            r"NOT WS-SEC-ACCESS-GRANTED\s+MOVE 'NOT AUTHORIZED FOR THIS POLICY BRANCH'",
        )

    def test_cancel_requires_cancel_profile(self):
        text = self.para("3100-AUTHORIZE-ENDORSEMENT")
        self.assertIn("END-TYPE-CANCEL", text)
        self.assertIn("'PAS.POLEND.CANCEL'", text)
        self.assertIn("NOT AUTHORIZED TO CANCEL POLICIES", text)

    def test_access_check_uses_query_security_update(self):
        text = self.para("3110-CHECK-UPDATE-ACCESS")
        self.assertIn("EXEC CICS QUERY SECURITY", text)
        self.assertIn("RESCLASS(WS-SEC-RESCLASS)", text)
        self.assertIn("RESID(WS-SEC-RESID)", text)
        self.assertIn("UPDATE(WS-SEC-UPDATE)", text)
        # fail closed: switch reset to 'N' before the EXEC CICS call
        self.assertRegex(
            text, r"MOVE 'N' TO WS-SEC-ACCESS-SW.*EXEC CICS QUERY SECURITY"
        )
        # granted only on NORMAL + UPDATABLE
        self.assertRegex(
            text,
            r"IF WS-RESPONSE-CODE = DFHRESP\(NORMAL\)\s+"
            r"AND WS-SEC-UPDATE = DFHVALUE\(UPDATABLE\)\s+"
            r"MOVE 'Y' TO WS-SEC-ACCESS-SW",
        )

    def test_writes_gated_on_validation(self):
        text = self.para("0000-MAIN-LOGIC")
        i3000 = text.find("PERFORM 3000-VALIDATE-ENDORSEMENT")
        i5000 = text.find("PERFORM 5000-APPLY-ENDORSEMENT")
        self.assertGreater(i3000, -1)
        self.assertGreater(i5000, i3000)
        gate = re.search(
            r"IF WS-ERROR-MSG = SPACES[^.]*PERFORM 5000-APPLY-ENDORSEMENT",
            text[i3000:],
        )
        self.assertIsNotNone(
            gate, "5000-APPLY-ENDORSEMENT not gated on WS-ERROR-MSG after 3000"
        )


if __name__ == "__main__":
    unittest.main()
