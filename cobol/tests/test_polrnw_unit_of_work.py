"""POLRNW (PRWL) renewal unit-of-work tests.

Compiles POLRNW with GnuCOBOL after replacing each EXEC CICS / EXEC SQL
block with a stub that records what the program did. The stubs model a
single CICS unit of work: an UPDATE marks its table as changed, SYNCPOINT
ROLLBACK discards the changes and RETURN reports what would be committed.
"""
import copy
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(
    os.environ.get("POLRNW_SOURCE", ROOT / "cobol/programs/POLRNW.cbl")
).resolve()
DEFAULT_FIXTURE = {
    "status": "AC",
    "premium": "1000.00",
    "expiry": "20261031",
    "policies_sqlcode": 0,
    "policies_rows": 1,
    "coverages_sqlcode": 0,
}
HARNESS_STORAGE = """\
       01  HARNESS-POLICIES-STATE    PIC X(09) VALUE 'UNCHANGED'.
       01  HARNESS-COVERAGES-STATE   PIC X(09) VALUE 'UNCHANGED'.
"""


def literal(value):
    if isinstance(value, int):
        return str(value)
    return "'" + str(value) + "'"


def stub(match, fixture):
    kind = match.group(1).upper()
    text = " ".join(match.group(2).split())
    period = match.group(3) or ""

    def arg(name):
        found = re.search(r"\b%s\s*\(\s*([^)]+?)\s*\)" % name, text, re.I)
        return found.group(1) if found else None

    if kind == "SQL" and re.search(r"\bUPDATE POLICIES\b", text, re.I):
        stmts = [
            "DISPLAY 'SQL UPDATE POLICIES EXPIRY ' POLICY-EXPIRY-DATE",
            "MOVE %d TO SQLCODE" % fixture["policies_sqlcode"],
            "MOVE %d TO SQLERRD(3)" % fixture["policies_rows"],
        ]
        if fixture["policies_sqlcode"] == 0 and fixture["policies_rows"]:
            stmts.append("MOVE 'CHANGED' TO HARNESS-POLICIES-STATE")
    elif kind == "SQL" and re.search(r"\bUPDATE COVERAGES\b", text, re.I):
        stmts = [
            "DISPLAY 'SQL UPDATE COVERAGES EXPIRY ' POLICY-EXPIRY-DATE",
            "MOVE %d TO SQLCODE" % fixture["coverages_sqlcode"],
        ]
        if fixture["coverages_sqlcode"] == 0:
            stmts.append("MOVE 'CHANGED' TO HARNESS-COVERAGES-STATE")
    elif kind == "SQL" and re.search(r"\bCOUNT\s*\(\s*\*\s*\)", text, re.I):
        stmts = ["MOVE 0 TO SQLCODE"]
    elif kind == "SQL" and re.match(r"SELECT\b", text, re.I):
        stmts = [
            "MOVE %s TO POLICY-STATUS" % literal(fixture["status"]),
            "MOVE %s TO POLICY-TOTAL-PREMIUM" % fixture["premium"],
            "MOVE %s TO POLICY-EXPIRY-DATE" % fixture["expiry"],
            "MOVE 2 TO POLICY-RENEWAL-COUNT",
            "MOVE 0 TO SQLCODE",
        ]
    elif kind == "CICS" and re.match(r"SYNCPOINT ROLLBACK\b", text, re.I):
        stmts = [
            "DISPLAY 'SYNCPOINT ROLLBACK'",
            "MOVE 'UNCHANGED' TO HARNESS-POLICIES-STATE",
            "MOVE 'UNCHANGED' TO HARNESS-COVERAGES-STATE",
        ]
        if arg("RESP"):
            stmts.append("MOVE 0 TO %s" % arg("RESP"))
    elif kind == "CICS" and re.match(r"ABEND\b", text, re.I):
        stmts = ["DISPLAY 'ABEND'", "STOP RUN"]
    elif kind == "CICS" and re.match(r"ASKTIME\b", text, re.I):
        stmts = ["MOVE 20261016 TO %s" % arg("ABSTIME")]
    elif kind == "CICS" and re.match(r"FORMATTIME\b", text, re.I):
        stmts = ["CONTINUE"]
    elif kind == "CICS" and re.match(r"ASSIGN\b", text, re.I):
        stmts = ["MOVE 'CSR0001' TO %s" % arg("USERID")]
        if arg("RESP"):
            stmts.append("MOVE 0 TO %s" % arg("RESP"))
    elif kind == "CICS" and re.match(r"QUERY SECURITY\b", text, re.I):
        stmts = ["MOVE 1 TO %s" % arg("UPDATE"), "MOVE 0 TO %s" % arg("RESP")]
    elif kind == "CICS" and re.match(r"RECEIVE\b", text, re.I):
        stmts = [
            "MOVE SPACES TO %s" % arg("INTO"),
            "MOVE 'POL000000001' TO POLICY-NUMBER",
            "MOVE 0 TO %s" % arg("RESP"),
        ]
    elif kind == "CICS" and re.match(r"SEND TEXT\b", text, re.I):
        stmts = ["DISPLAY 'SEND ERROR ' %s" % arg("FROM")]
    elif kind == "CICS" and re.match(r"SEND\b", text, re.I):
        stmts = ["DISPLAY 'SEND CONFIRMATION'"]
    elif kind == "CICS" and re.match(r"RETURN\b", text, re.I):
        stmts = [
            "DISPLAY 'COMMIT POLICIES ' HARNESS-POLICIES-STATE",
            "DISPLAY 'COMMIT COVERAGES ' HARNESS-COVERAGES-STATE",
            "GOBACK",
        ]
    else:
        raise ValueError("No stub for EXEC %s %s" % (kind, text))

    # The first statement replaces "EXEC" in place, so it keeps the
    # original indentation; the rest start in area B.
    line_start = match.string.rfind("\n", 0, match.start()) + 1
    indent = " " * (match.start() - line_start)
    for s in stmts:
        if len(indent) + len(s) > 72:
            raise ValueError("Stub line exceeds column 72: " + s)
    return ("\n" + indent).join(stmts) + period


def transformed_source(fixture):
    source = SOURCE.read_text()
    source = re.sub(
        r"(?im)^\s*COPY\s+([A-Z0-9-]+)\.\s*$",
        lambda m: (ROOT / "cobol/copybooks" / (m.group(1) + ".cpy"))
        .read_text(),
        source,
    )
    source = re.sub(
        r"EXEC\s+SQL\s+INCLUDE\s+SQLCA\s+END-EXEC\.",
        "       01  SQLCA.\n"
        "           05  SQLCODE PIC S9(9) COMP-5.\n"
        "           05  SQLERRD PIC S9(9) COMP-5 OCCURS 6.\n"
        + HARNESS_STORAGE,
        source,
        flags=re.I,
    )
    source = re.sub(r"(?m)^       LINKAGE SECTION\.\s*\n", "", source)
    source = re.sub(r"DFHRESP\s*\(\s*NORMAL\s*\)", "0", source)
    source = re.sub(r"DFHVALUE\s*\(\s*UPDATABLE\s*\)", "1", source)
    return re.sub(
        r"EXEC\s+(CICS|SQL)\b(.*?)END-EXEC(\.)?",
        lambda m: stub(m, fixture),
        source,
        flags=re.I | re.S,
    )


class PolrnwUnitOfWorkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("cobc") is None:
            raise unittest.SkipTest("GnuCOBOL 'cobc' is not installed")

    def run_program(self, **overrides):
        fixture = copy.deepcopy(DEFAULT_FIXTURE)
        fixture.update(overrides)
        with tempfile.TemporaryDirectory(prefix="polrnw-uow-") as tmp:
            src = Path(tmp) / "POLRNW.cbl"
            exe = Path(tmp) / "polrnw"
            src.write_text(transformed_source(fixture))
            built = subprocess.run(
                ["cobc", "-x", "-o", str(exe), str(src)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run(
                [str(exe)],
                capture_output=True,
                text=True,
                env=dict(os.environ, COB_SET_DEBUG="N"),
            )
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertNotIn("libcob:", ran.stderr)
            return ran.stdout

    def assert_nothing_committed(self, out):
        self.assertIn("COMMIT POLICIES UNCHANGED", out)
        self.assertIn("COMMIT COVERAGES UNCHANGED", out)
        self.assertNotIn("SEND CONFIRMATION", out)

    def test_successful_renewal_commits_both_tables(self):
        out = self.run_program()
        self.assertIn("SQL UPDATE POLICIES EXPIRY 20271031", out)
        self.assertIn("SQL UPDATE COVERAGES EXPIRY 20271031", out)
        self.assertIn("SEND CONFIRMATION", out)
        self.assertNotIn("SEND ERROR", out)
        self.assertNotIn("SYNCPOINT ROLLBACK", out)
        self.assertIn("COMMIT POLICIES CHANGED", out)
        self.assertIn("COMMIT COVERAGES CHANGED", out)

    def test_policies_update_failure_skips_coverages_and_shows_error(self):
        out = self.run_program(policies_sqlcode=-911)
        self.assertNotIn("SQL UPDATE COVERAGES", out)
        self.assertIn("SYNCPOINT ROLLBACK", out)
        self.assertIn("SEND ERROR DB2 ERROR UPDATING RENEWAL", out)
        self.assert_nothing_committed(out)

    def test_policies_update_matching_no_row_is_rolled_back(self):
        out = self.run_program(policies_rows=0)
        self.assertNotIn("SQL UPDATE COVERAGES", out)
        self.assertIn("SYNCPOINT ROLLBACK", out)
        self.assertIn(
            "SEND ERROR RENEWAL NOT APPLIED - POLICY NOT UPDATED", out
        )
        self.assert_nothing_committed(out)

    def test_coverages_update_failure_rolls_back_policy_update(self):
        out = self.run_program(coverages_sqlcode=-904)
        self.assertIn("SQL UPDATE POLICIES", out)
        self.assertIn("SQL UPDATE COVERAGES", out)
        self.assertIn("SYNCPOINT ROLLBACK", out)
        self.assertIn("SEND ERROR DB2 ERROR UPDATING COVERAGES", out)
        self.assert_nothing_committed(out)

    def test_policy_without_active_coverages_still_renews(self):
        out = self.run_program(coverages_sqlcode=100)
        self.assertIn("SEND CONFIRMATION", out)
        self.assertNotIn("SYNCPOINT ROLLBACK", out)
        self.assertIn("COMMIT POLICIES CHANGED", out)

    def test_leap_day_expiry_renews_to_28_february(self):
        out = self.run_program(expiry="20280229")
        self.assertIn("SQL UPDATE POLICIES EXPIRY 20290228", out)
        self.assertIn("SQL UPDATE COVERAGES EXPIRY 20290228", out)
        self.assertIn("SEND CONFIRMATION", out)

    def test_28_february_into_leap_year_keeps_28_february(self):
        out = self.run_program(expiry="20270228")
        self.assertIn("SQL UPDATE POLICIES EXPIRY 20280228", out)

    def test_invalid_stored_expiry_is_rejected_before_any_update(self):
        out = self.run_program(expiry="20261331")
        self.assertNotIn("SQL UPDATE", out)
        self.assertIn("SEND ERROR INVALID EXPIRY DATE ON POLICY", out)
        self.assert_nothing_committed(out)


if __name__ == "__main__":
    unittest.main()
