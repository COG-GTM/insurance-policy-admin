"""Renewal eligibility tests for POLRNW (CICS transaction PRWL).

POLRNW is compiled with GnuCOBOL after every EXEC CICS / EXEC SQL block is
replaced by COBOL that plays back a fixture, so the real eligibility and
renewal paragraphs run unchanged.
"""
import copy
import datetime
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
RENEWAL_WINDOW_DAYS = 60

DEFAULT_FIXTURE = {
    "today": "20261016",
    "policy_number": "POL000000001",
    "policy_type": "HOM",
    "status": "AC",
    "premium": "1000.00",
    "expiry": "20261101",
    "renewal_count": 2,
    "uw_status": "AP",
    "select_sqlcode": 0,
    "open_claims": 0,
    "claims_sqlcode": 0,
    "uw_decision": "AP",
    "uw_decision_date": "20261010",
    "update_rows": 1,
    "coverage_sqlcode": 0,
}
POLICY_COLUMNS = {
    "POLICY_NUMBER": "policy_number",
    "POLICY_TYPE": "policy_type",
    "POLICY_STATUS": "status",
    "TOTAL_PREMIUM": "premium",
    "EXPIRY_DATE": "expiry",
    "RENEWAL_COUNT": "renewal_count",
    "UW_STATUS": "uw_status",
}


def literal(value):
    if value is None or value == "":
        return "SPACES"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def ymd(value):
    return datetime.datetime.strptime(value, "%Y%m%d").date()


def decision_in_window(fixture):
    start = ymd(fixture["expiry"]) - datetime.timedelta(days=RENEWAL_WINDOW_DAYS)
    return "Y" if ymd(fixture["uw_decision_date"]) >= start else "N"


def option(name, text):
    match = re.search(rf"\b{name}\s*\(\s*([^)]+)\)", text, re.I)
    if not match:
        raise ValueError(f"{name}() missing in: {text}")
    return match.group(1).strip()


def stub_statements(kind, block, fixture):
    text = " ".join(block.split())
    if kind == "CICS":
        if re.match(r"ASKTIME\b", text, re.I):
            return [f"MOVE 0 TO {option('ABSTIME', text)}"]
        if re.match(r"FORMATTIME\b", text, re.I):
            return [f"MOVE {fixture['today']} TO {option('YYYYMMDD', text)}"]
        if re.match(r"RECEIVE\b", text, re.I):
            return [
                f"MOVE SPACES TO {option('INTO', text)}",
                f"MOVE {literal(fixture['policy_number'])} TO POLICY-NUMBER",
                f"MOVE 0 TO {option('RESP', text)}",
            ]
        if re.fullmatch(r"SYNCPOINT ROLLBACK", text, re.I):
            return ["DISPLAY 'SYNCPOINT ROLLBACK'"]
        if re.match(r"SEND TEXT\b", text, re.I):
            return [f"DISPLAY 'SEND ERROR ' {option('FROM', text)}"]
        if re.match(r"SEND\b", text, re.I):
            return ["DISPLAY 'SEND CONFIRMATION'"]
        if re.match(r"RETURN\b", text, re.I):
            return ["GOBACK"]
    else:
        if re.match(r"SELECT COUNT\(\*\)", text, re.I) and "CLAIMS" in text:
            host = re.search(r"INTO\s*:\s*([A-Z0-9-]+)", text, re.I).group(1)
            return [
                f"MOVE {fixture['open_claims']} TO {host}",
                f"MOVE {fixture['claims_sqlcode']} TO SQLCODE",
            ]
        if re.match(r"SELECT D\.DECISION_CODE\b", text, re.I):
            code, window = re.findall(r":\s*(WS-[A-Z0-9-]+)", text.split("FROM")[0])[-2:]
            if fixture["uw_decision"] is None:
                return ["MOVE 100 TO SQLCODE"]
            return [
                f"MOVE {literal(fixture['uw_decision'])} TO {code}",
                f"MOVE {literal(decision_in_window(fixture))} TO {window}",
                "MOVE 0 TO SQLCODE",
            ]
        if re.match(r"SELECT\b", text, re.I) and re.search(r"FROM POLICIES\b", text):
            m = re.search(r"SELECT (.*?) INTO (.*?) FROM", text, re.I)
            columns = [c.strip().upper() for c in m.group(1).split(",")]
            hosts = re.findall(r":\s*([A-Z0-9-]+)", m.group(2))
            out = [
                f"MOVE {literal(fixture[POLICY_COLUMNS[c]])} TO {h}"
                for c, h in zip(columns, hosts)
            ]
            return out + [f"MOVE {fixture['select_sqlcode']} TO SQLCODE"]
        if re.match(r"UPDATE POLICIES\b", text, re.I):
            return [
                "DISPLAY 'SQL UPDATE POLICIES STATUS=' POLICY-STATUS",
                "' UW=' POLICY-UW-STATUS ' EXPIRY=' POLICY-EXPIRY-DATE",
                "MOVE 0 TO SQLCODE",
                f"MOVE {fixture['update_rows']} TO SQLERRD(3)",
            ]
        if re.match(r"UPDATE COVERAGES\b", text, re.I):
            return [
                "DISPLAY 'SQL UPDATE COVERAGES'",
                f"MOVE {fixture['coverage_sqlcode']} TO SQLCODE",
            ]
    raise ValueError(f"No stub for EXEC {kind}: {text}")


def stub_block(match, fixture):
    lines = ["           " + s for s in stub_statements(
        match.group(1).upper(), match.group(2), fixture)]
    for line in lines:
        if len(line) > 72:
            raise ValueError(f"stub line past column 72: {line}")
    if match.group(3):
        lines.append("           .")
    return "\n".join(lines)


def build_source(fixture):
    source = SOURCE.read_text()
    source = re.sub(
        r"(?im)^\s*COPY\s+([A-Z0-9-]+)\.\s*$",
        lambda m: (ROOT / "cobol/copybooks" / f"{m.group(1)}.cpy").read_text(),
        source,
    )
    source = re.sub(
        r"EXEC\s+SQL\s+INCLUDE\s+SQLCA\s+END-EXEC\.",
        "       01 SQLCA.\n"
        "          05 SQLCODE PIC S9(9) COMP-5.\n"
        "          05 SQLERRD PIC S9(9) COMP-5 OCCURS 6.",
        source,
    )
    source = re.sub(r"(?m)^       LINKAGE SECTION\.\n", "", source)
    source = source.replace("DFHRESP(NORMAL)", "0")
    return re.sub(
        r"^ *EXEC\s+(CICS|SQL)\s+(.*?)END-EXEC(\.)?",
        lambda m: stub_block(m, fixture),
        source,
        flags=re.S | re.M,
    )


class PolrnwRenewalRulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("cobc") is None:
            raise unittest.SkipTest("GnuCOBOL 'cobc' is not installed")

    def run_polrnw(self, **overrides):
        fixture = copy.deepcopy(DEFAULT_FIXTURE)
        fixture.update(overrides)
        with tempfile.TemporaryDirectory(prefix="polrnw-") as tmp:
            src = Path(tmp) / "POLRNW.cbl"
            exe = Path(tmp) / "polrnw"
            src.write_text(build_source(fixture))
            built = subprocess.run(
                ["cobc", "-x", "-o", str(exe), str(src)],
                capture_output=True, text=True,
            )
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            return ran.stdout

    def assert_renewed(self, out):
        self.assertIn("SQL UPDATE POLICIES STATUS=AC UW=AP", out)
        self.assertIn("SQL UPDATE COVERAGES", out)
        self.assertIn("SEND CONFIRMATION", out)
        self.assertNotIn("SEND ERROR", out)
        self.assertNotIn("SYNCPOINT ROLLBACK", out)

    def assert_rejected(self, out, message):
        self.assertNotIn("SQL UPDATE", out)
        self.assertNotIn("SEND CONFIRMATION", out)
        self.assertIn("SEND ERROR " + message, out)

    def test_approved_policy_in_window_renews_without_pending_uw(self):
        self.assert_renewed(self.run_polrnw())

    def test_expired_policy_inside_grace_period_renews(self):
        self.assert_renewed(self.run_polrnw(
            status="EX", expiry="20261001", uw_decision_date="20261012"))

    def test_more_than_three_open_claims_rejected(self):
        self.assert_rejected(self.run_polrnw(open_claims=4),
                             "RENEWAL BLOCKED - TOO MANY OPEN CLAIMS")

    def test_three_open_claims_allowed(self):
        self.assert_renewed(self.run_polrnw(open_claims=3))

    def test_open_claims_query_failure_fails_closed(self):
        self.assert_rejected(self.run_polrnw(claims_sqlcode=-904),
                             "DB2 ERROR CHECKING OPEN CLAIMS")

    def test_declined_policy_rejected(self):
        self.assert_rejected(self.run_polrnw(uw_status="DC"),
                             "RENEWAL BLOCKED - UNDERWRITING DECLINED")

    def test_referred_policy_rejected(self):
        self.assert_rejected(self.run_polrnw(uw_status="RF"),
                             "RENEWAL BLOCKED - UNDERWRITING REFERRED")

    def test_pending_or_senior_referral_rejected(self):
        for uw in ("PN", "RS", "RM"):
            with self.subTest(uw=uw):
                self.assert_rejected(
                    self.run_polrnw(uw_status=uw),
                    "RENEWAL BLOCKED - UNDERWRITING NOT APPROVED")

    def test_finding_scenario_expired_declined_four_claims_rejected(self):
        self.assert_rejected(
            self.run_polrnw(status="EX", expiry="20261001",
                            uw_status="DC", open_claims=4),
            "RENEWAL BLOCKED - UNDERWRITING DECLINED")

    def test_before_renewal_window_rejected(self):
        self.assert_rejected(self.run_polrnw(expiry="20270301"),
                             "RENEWAL BLOCKED - OUTSIDE RENEWAL WINDOW")

    def test_after_grace_period_rejected(self):
        self.assert_rejected(
            self.run_polrnw(status="EX", expiry="20260901"),
            "RENEWAL BLOCKED - GRACE PERIOD ENDED")

    def test_no_underwriting_decision_rejected(self):
        self.assert_rejected(self.run_polrnw(uw_decision=None),
                             "RENEWAL BLOCKED - NO UNDERWRITING DECISION")

    def test_latest_decision_not_approved_rejected(self):
        self.assert_rejected(self.run_polrnw(uw_decision="RS"),
                             "RENEWAL BLOCKED - UNDERWRITING NOT APPROVED")

    def test_stale_approval_from_previous_term_rejected(self):
        self.assert_rejected(
            self.run_polrnw(uw_decision_date="20251020"),
            "RENEWAL BLOCKED - UNDERWRITING REVIEW REQUIRED")

    def test_cancelled_policy_rejected(self):
        self.assert_rejected(self.run_polrnw(status="CN"),
                             "POLICY NOT ELIGIBLE FOR RENEWAL")

    def test_second_renewal_of_renewed_policy_rejected(self):
        first = self.run_polrnw()
        self.assert_renewed(first)
        new_expiry = re.search(r"EXPIRY=(\d{8})", first).group(1)
        self.assertEqual(new_expiry, "20271101")
        second = self.run_polrnw(expiry=new_expiry, renewal_count=3)
        self.assert_rejected(second, "RENEWAL BLOCKED - OUTSIDE RENEWAL WINDOW")

    def test_concurrent_change_rolls_back(self):
        out = self.run_polrnw(update_rows=0)
        self.assertNotIn("SQL UPDATE COVERAGES", out)
        self.assertNotIn("SEND CONFIRMATION", out)
        self.assertIn("SYNCPOINT ROLLBACK", out)
        self.assertIn("SEND ERROR RENEWAL NOT APPLIED - POLICY CHANGED", out)

    def test_coverage_update_failure_rolls_back(self):
        out = self.run_polrnw(coverage_sqlcode=-911)
        self.assertIn("SYNCPOINT ROLLBACK", out)
        self.assertIn("SEND ERROR DB2 ERROR UPDATING COVERAGES", out)
        self.assertNotIn("SEND CONFIRMATION", out)

    def test_policy_not_found_stops_before_eligibility(self):
        out = self.run_polrnw(select_sqlcode=100)
        self.assert_rejected(out, "POLICY NOT FOUND FOR RENEWAL")
        self.assertEqual(out.count("SEND ERROR"), 1)


if __name__ == "__main__":
    unittest.main()
