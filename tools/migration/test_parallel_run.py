import json
import tempfile
import unittest
from pathlib import Path

import parallel_run as pr

PLAN = {
    "cycle": {"start": "2026-11-02", "end": "2026-11-04", "min_business_days": 3, "holidays": []},
    "outputs": [
        {"pattern": "PREMRPT*.txt", "job": "premiumRecalculation", "required": True},
        {"pattern": "policy_extract.dat", "job": "claimsDailyExtract", "key": [0, 12]},
    ],
    "runbook": [{"id": "RB-1", "execute_on_branch": False, "dry_run_default": True}],
    "sign_offs": [{"role": "Product owner", "status": "pending"}],
}
QUIRKS = {"quirks": [{"id": "Q-03", "proposed_decision": "fix"}, {"id": "Q-05", "proposed_decision": "preserve"}]}


def triage(*entries):
    return {"entries": list(entries)}


class ParallelRunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def day(self, root, date, files):
        d = self.tmp / root / date
        d.mkdir(parents=True, exist_ok=True)
        for name, data in files.items():
            (d / name).write_bytes(data)

    def test_identical_outputs_are_clean(self):
        files = {"PREMRPT.txt": b"HDR\r\nLINE\r\n", "policy_extract.dat": b"POL000000001AAA\n"}
        self.day("cap", "2026-11-02", files)
        self.day("cand", "2026-11-02", files)
        report = pr.compare_day("2026-11-02", self.tmp / "cap/2026-11-02", self.tmp / "cand/2026-11-02", PLAN, triage())
        self.assertEqual("CLEAN", report["status"])
        self.assertEqual([], report["differences"])

    def test_differences_are_keyed_without_content_and_unexplained_until_triaged(self):
        self.day("cap", "2026-11-02", {"PREMRPT.txt": b"A\n", "policy_extract.dat": b"POL000000001SECRET1\nPOL000000002X\n"})
        self.day("cand", "2026-11-02", {"PREMRPT.txt": b"A\n", "policy_extract.dat": b"POL000000001SECRET2\n"})
        report = pr.compare_day("2026-11-02", self.tmp / "cap/2026-11-02", self.tmp / "cand/2026-11-02", PLAN, triage())
        self.assertEqual("NOT_CLEAN", report["status"])
        self.assertEqual(["POL000000001", "POL000000002"], [d["key"] for d in report["differences"]])
        self.assertEqual(["changed", "missing"], [d["kind"] for d in report["differences"]])
        self.assertNotIn("SECRET", json.dumps(report) + pr.markdown(report))
        self.assertEqual(2, report["counts"]["unexplained"])

    def test_triage_classifies_and_open_bugs_block(self):
        self.day("cap", "2026-11-02", {"PREMRPT.txt": b"A\n", "policy_extract.dat": b"POL000000001A\nPOL000000002A\n"})
        self.day("cand", "2026-11-02", {"PREMRPT.txt": b"A\n", "policy_extract.dat": b"POL000000001B\nPOL000000002B\n"})
        register = triage(
            {"id": "PRT-001", "classification": "approved_fix", "quirk": "Q-03", "files": "policy_extract.dat",
             "key_regex": "POL000000001", "reason": "r"},
            {"id": "PRT-002", "classification": "bug", "reference": "FU-529-9", "files": "policy_extract.dat",
             "key_regex": "POL000000002", "reason": "r", "status": "open"})
        report = pr.compare_day("2026-11-02", self.tmp / "cap/2026-11-02", self.tmp / "cand/2026-11-02", PLAN, register)
        self.assertEqual({"bug": 1, "approved_fix": 1, "data_issue": 0, "unexplained": 0}, report["counts"])
        self.assertEqual("NOT_CLEAN", report["status"])
        register["entries"][1]["status"] = "resolved"
        report = pr.compare_day("2026-11-02", self.tmp / "cap/2026-11-02", self.tmp / "cand/2026-11-02", PLAN, register)
        self.assertEqual("NOT_CLEAN", report["status"], "a resolved bug still blocks until the outputs are recaptured")
        self.assertEqual(0, report["open_bugs"])
        self.day("cand", "2026-11-02", {"policy_extract.dat": b"POL000000001B\nPOL000000002A\n"})
        report = pr.compare_day("2026-11-02", self.tmp / "cap/2026-11-02", self.tmp / "cand/2026-11-02", PLAN, register)
        self.assertEqual("CLEAN", report["status"])

    def test_extra_tracked_candidate_files_are_differences(self):
        self.day("cap", "2026-11-03", {"PREMRPT.D1.txt": b"A\n"})
        self.day("cand", "2026-11-03", {"PREMRPT.D1.txt": b"A\n", "PREMRPT.D2.txt": b"B\n", "notes.log": b"x\n"})
        report = pr.compare_day("2026-11-03", self.tmp / "cap/2026-11-03", self.tmp / "cand/2026-11-03", PLAN, triage())
        self.assertEqual("NOT_CLEAN", report["status"])
        self.assertEqual([("PREMRPT.D2.txt", "extra_file")], [(d["file"], d["kind"]) for d in report["differences"]])

    def test_monthly_exposure_is_required_on_the_first_business_day_of_the_month(self):
        files = {"PREMRPT.txt": b"A\n"}
        for date in ("2026-11-02", "2026-11-03"):
            self.day("cap", date, files)
            self.day("cand", date, files)
        plan = {**PLAN, "outputs": PLAN["outputs"] + [
            {"pattern": "exposure_monthly.csv", "job": "actuarialMonthlyExposure",
             "required_on": "first_business_day_of_month"}]}
        first = pr.compare_day("2026-11-02", self.tmp / "cap/2026-11-02", self.tmp / "cand/2026-11-02", plan, triage())
        self.assertEqual(["exposure_monthly.csv"], first["missing_outputs"])
        self.assertEqual("NOT_CLEAN", first["status"])
        second = pr.compare_day("2026-11-03", self.tmp / "cap/2026-11-03", self.tmp / "cand/2026-11-03", plan, triage())
        self.assertEqual("CLEAN", second["status"])

    def test_missing_candidate_file_and_required_output(self):
        self.day("cap", "2026-11-02", {"policy_extract.dat": b"POL000000001A\n"})
        (self.tmp / "cand/2026-11-02").mkdir(parents=True)
        report = pr.compare_day("2026-11-02", self.tmp / "cap/2026-11-02", self.tmp / "cand/2026-11-02", PLAN, triage())
        self.assertEqual(["PREMRPT*.txt"], report["missing_outputs"])
        self.assertEqual("missing_file", report["differences"][0]["kind"])

    def test_gate_needs_every_business_day_of_the_cycle_clean(self):
        clean = {"status": "CLEAN", "counts": {"unexplained": 0, "bug": 0}, "open_bugs": 0, "missing_outputs": []}
        reports = {d: dict(clean, business_date=d) for d in ("2026-11-02", "2026-11-03")}
        result = pr.gate(PLAN, reports)
        self.assertFalse(result["passed"])
        self.assertIn("2026-11-04: no parallel-run report", result["problems"])
        reports["2026-11-04"] = dict(clean, business_date="2026-11-04")
        self.assertTrue(pr.gate(PLAN, reports)["passed"])
        short = {**PLAN, "cycle": {**PLAN["cycle"], "end": "2026-11-02"}}
        result = pr.gate(short, reports)
        self.assertFalse(result["passed"])
        self.assertIn("cycle has 1 business days; at least 3 are required", result["problems"])
        self.assertFalse(pr.gate({**PLAN, "cycle": {"start": None, "end": None}}, reports)["passed"])

    def test_business_days_skip_weekends_and_holidays(self):
        import datetime as dt
        days = pr.business_days(dt.date(2026, 11, 6), dt.date(2026, 11, 11), {"2026-11-11"})
        self.assertEqual(["2026-11-06", "2026-11-09", "2026-11-10"], days)

    def test_check_rejects_bad_triage_and_executable_steps(self):
        bad = triage(
            {"id": "PRT-001", "classification": "approved_fix", "quirk": "Q-05", "files": "x", "reason": "r"},
            {"id": "PRT-001", "classification": "bug", "files": "*", "reason": ""},
            {"id": "PRT-003", "classification": "ignored", "files": "x", "reason": "r"})
        plan = {**PLAN, "runbook": [{"id": "RB-1", "execute_on_branch": True, "dry_run_default": False}],
                "sign_offs": [{"role": "PO", "status": "approved"}]}
        problems = pr.check(plan, bad, QUIRKS)
        joined = "\n".join(problems)
        for expected in ("quirk whose decision is 'fix'", "unique", "needs a reference", "reason is required",
                         "catch-all", "classification must be", "execute_on_branch", "dry_run_default", "evidence"):
            self.assertIn(expected, joined)

    def test_repository_plan_and_triage_register_are_valid(self):
        self.assertEqual(0, pr.main(["check"]))


if __name__ == "__main__":
    unittest.main()
