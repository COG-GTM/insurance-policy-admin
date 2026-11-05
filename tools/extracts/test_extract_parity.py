import json
import os
import tempfile
import unittest

import extract_parity as ep


def write(root, run, name, data):
    os.makedirs(os.path.join(root, run), exist_ok=True)
    with open(os.path.join(root, run, name), "wb") as f:
        f.write(data)


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cap = os.path.join(self.tmp.name, "captured")
        self.cand = os.path.join(self.tmp.name, "candidate")
        os.makedirs(self.cap)

    def tearDown(self):
        self.tmp.cleanup()

    def runs(self, n, differ_on=None):
        for i in range(n):
            run = f"2026-11-0{i + 1}"
            rec = b"POL000000001".ljust(200) + b"\r\n"
            write(self.cap, run, "policy_extract.dat", rec)
            write(self.cand, run, "policy_extract.dat",
                  rec.replace(b"POL000000001", b"POL000000009") if i == differ_on else rec)

    def test_five_consecutive_identical_runs_pass(self):
        self.runs(5)
        report = ep.compare_runs(self.cap, self.cand, 5)
        self.assertTrue(report["passed"])
        self.assertEqual(report["feeds"]["policy_extract.dat"]["consecutive_identical"], 5)

    def test_a_difference_breaks_the_streak_and_is_located(self):
        self.runs(6, differ_on=2)
        report = ep.compare_runs(self.cap, self.cand, 5)
        self.assertFalse(report["passed"])
        feed = report["feeds"]["policy_extract.dat"]
        self.assertEqual(feed["consecutive_identical"], 3)
        self.assertEqual(feed["runs"][2]["detail"], "record 1 differs at byte 12 (lengths 200 vs 200)")

    def test_missing_candidate_and_no_captures_fail(self):
        self.assertFalse(ep.compare_runs(self.cap, self.cand, 5)["passed"])
        write(self.cap, "2026-11-01", "coverage_extract.dat", b"x\r\n")
        report = ep.compare_runs(self.cap, self.cand, 1)
        self.assertEqual(report["feeds"]["coverage_extract.dat"]["runs"][0]["detail"],
                         "candidate file missing")

    def test_exposure_order_only_difference_is_reported_but_not_identical(self):
        head = b"POLICY_TYPE,BRANCH_CODE\r\n"
        a, b = b"AUT,NE01,BI,1\r\n", b"AUT,NE01,PD,2\r\n"
        write(self.cap, "2026-11-01", "exposure_monthly.csv", head + a + b)
        write(self.cand, "2026-11-01", "exposure_monthly.csv", head + b + a)
        run = ep.compare_runs(self.cap, self.cand, 1)["feeds"]["exposure_monthly.csv"]["runs"][0]
        self.assertFalse(run["identical"])
        self.assertIn("Q-34", run["detail"])
        lenient = ep.compare_runs(self.cap, self.cand, 1, exposure_order_insensitive=True)
        self.assertTrue(lenient["passed"])
        self.assertFalse(lenient["feeds"]["exposure_monthly.csv"]["runs"][0]["identical"])
        self.assertFalse(ep.compare_runs(self.cap, self.cand, 1)["passed"])

    def test_terminator_difference_is_detected(self):
        self.assertEqual(ep.compare_bytes("x", b"a\r\n", b"a\n"), "record terminators or encoding differ")


class GateTest(unittest.TestCase):
    def test_committed_cutover_file_is_valid_and_pending(self):
        self.assertEqual(ep.gate(ep.CUTOVER, check_only=True), 0)
        self.assertEqual(ep.gate(ep.CUTOVER, check_only=False), 3)

    def test_retirement_steps_may_never_execute_on_the_branch(self):
        with open(ep.CUTOVER, encoding="utf-8") as f:
            doc = json.load(f)
        doc["legacy_retirement"][0]["execute_on_branch"] = True
        doc["feeds"][0]["cutover_date"] = "next week"
        errors = ep.validate_cutover(doc)
        self.assertTrue(any("never execute" in e for e in errors))
        self.assertTrue(any("YYYY-MM-DD or TBD" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
