import copy
import datetime as dt
import io
import json
import os
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout

import cutover as co

NOW = dt.datetime(2027, 3, 20, tzinfo=dt.timezone.utc)


def approve_everything(plan, root):
    """A copy of the plan whose prerequisite plans are all closed, in a temp copy of the docs."""
    plan = copy.deepcopy(plan)
    for prereq in plan["prerequisites"]:
        with open(os.path.join(co.ROOT, prereq["plan"]), encoding="utf-8") as f:
            data = json.load(f)
        for collection in prereq["collections"]:
            rule = prereq.get("entry_rules", {}).get(collection, {})
            for entry in co.lookup(data, collection):
                if "status" in entry:
                    entry["status"] = rule.get("done", ["approved"])[0]
                else:
                    entry["result"] = "passed"
                if "evidence" in entry:
                    entry["evidence"] = "CHG-1"
                for field in rule.get("fields", []):
                    entry[field] = "2027-01-01"
        for field in prereq["required_fields"]:
            parent, _, leaf = field.rpartition(".")
            (co.lookup(data, parent) if parent else data)[leaf] = "2027-01-01"
        path = os.path.join(root, prereq["plan"])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f)
    plan["freeze_window"].update(start="2027-03-06T22:00:00-05:00", end="2027-03-07T06:00:00-05:00")
    for crit in plan["go_no_go"]:
        crit.update(status="approved", evidence="CHG-1")
    for owner in plan["owners"]:
        owner["name"] = "someone"
    return plan


class CutoverPlanTest(unittest.TestCase):
    def setUp(self):
        self.plan = co.fr.load(co.PLAN)

    def test_committed_plan_is_valid_and_no_go(self):
        self.assertEqual(co.validate(self.plan), [])
        go, reasons = co.decision(self.plan)
        self.assertFalse(go)
        self.assertTrue(any(r.startswith("P1 ") for r in reasons))
        self.assertIn("freeze window not agreed", reasons)
        self.assertEqual({c["status"] for c in self.plan["go_no_go"]}, {"pending"})

    def test_capabilities_follow_the_adr_table(self):
        plan = copy.deepcopy(self.plan)
        del plan["capabilities"][3]
        self.assertTrue(any("ADR order" in e for e in co.validate(plan)))

    def test_every_capability_needs_a_switch_back(self):
        plan = copy.deepcopy(self.plan)
        plan["capabilities"][0]["rollback"] = ""
        self.assertIn("C-1: missing rollback", co.validate(plan))

    def test_unknown_smoke_test_and_bad_state_are_reported(self):
        plan = copy.deepcopy(self.plan)
        plan["capabilities"][2]["smoke"] = ["SM-99"]
        plan["capabilities"][4]["state"] = "DONE"
        errors = co.validate(plan)
        self.assertIn("C-3: unknown smoke test SM-99", errors)
        self.assertTrue(any(e.startswith("C-5: state") for e in errors))

    def test_no_step_may_run_from_a_branch(self):
        plan = copy.deepcopy(self.plan)
        plan["steps"][0]["execute_on_branch"] = True
        self.assertIn("CO-01: execute_on_branch must be false", co.validate(plan))

    def test_owners_must_be_known_roles(self):
        plan = copy.deepcopy(self.plan)
        plan["go_no_go"][0]["owner"] = "Somebody else"
        plan["steps"][2]["owner"] = "Mainframe team + Nobody"
        errors = co.validate(plan)
        self.assertIn("GN-01: owner must be a role from 'owners'", errors)
        self.assertTrue(any(e.startswith("CO-03:") for e in errors))

    def test_owner_must_match_a_role_exactly(self):
        roles = [o["role"] for o in self.plan["owners"]]
        self.assertTrue(co.role_of("Cutover lead", roles))
        self.assertTrue(co.role_of("DBA + Mainframe team", roles))
        for owner in (None, "", " ", "C", "Cutover", "DBA +", "Cutover lead (release manager"):
            self.assertFalse(co.role_of(owner, roles), owner)

    def test_rollback_needs_a_decision_owner(self):
        for value in (None, "", "C"):
            plan = copy.deepcopy(self.plan)
            if value is None:
                del plan["rollback"]["decision_owner"]
            else:
                plan["rollback"]["decision_owner"] = value
            self.assertIn("rollback: decision_owner must be a role from 'owners'", co.validate(plan), value)

    def test_approved_criterion_needs_evidence(self):
        plan = copy.deepcopy(self.plan)
        plan["go_no_go"][1]["status"] = "approved"
        self.assertIn("GN-02: approved without evidence", co.validate(plan))

    def test_prerequisite_gate_ids_must_exist(self):
        plan = copy.deepcopy(self.plan)
        plan["prerequisites"][2]["ids"] = ["G1", "G42"]
        self.assertTrue(any("G42 not found" in e for e in co.validate(plan)))

    def test_freeze_window_is_bounded(self):
        plan = copy.deepcopy(self.plan)
        plan["freeze_window"].update(start="2027-03-06T22:00:00", end="2027-03-07T22:00:00")
        self.assertTrue(any(e.startswith("freeze_window: 24 hours") for e in co.validate(plan)))
        plan["freeze_window"]["end"] = None
        self.assertIn("freeze_window: set both start and end, or neither", co.validate(plan))

    def test_go_only_when_everything_is_closed(self):
        with tempfile.TemporaryDirectory() as root:
            plan = approve_everything(self.plan, root)
            self.assertEqual(co.decision(plan, root), (True, []))
            plan["owners"][0]["name"] = None
            go, reasons = co.decision(plan, root)
            self.assertFalse(go)
            self.assertEqual(reasons, ["1 owner roles without a named person"])

    def test_gate_exit_code(self):
        with redirect_stdout(io.StringIO()) as out:
            self.assertEqual(co.main(["--gate"]), 3)
            self.assertEqual(co.main([]), 0)
        self.assertIn("NO-GO", out.getvalue())
        self.assertIn("nothing switched", out.getvalue())


class HypercareTest(unittest.TestCase):
    def setUp(self):
        self.plan = copy.deepcopy(co.fr.load(co.PLAN))

    def test_not_started_before_cutover(self):
        self.assertEqual(co.hypercare(self.plan, NOW)[0], "not-started")
        with redirect_stdout(io.StringIO()):
            self.assertEqual(co.main(["--hypercare", "--gate"]), 3)

    def test_running_passed_and_breached(self):
        self.plan["hypercare"]["cutover_completed_at"] = "2027-03-07T06:00:00-05:00"
        self.assertEqual(co.hypercare(self.plan, NOW)[0], "running")
        status, detail = co.hypercare(self.plan, NOW + dt.timedelta(days=2))
        self.assertEqual(status, "running")
        self.assertIn("still waiting for SM-05, SM-06", detail)
        for check in self.plan["hypercare"]["checks"]:
            check.update(status="passed", evidence="CHG-2")
        self.assertEqual(co.hypercare(self.plan, NOW + dt.timedelta(days=2))[0], "passed")
        self.plan["hypercare"]["incidents"] = [
            {"id": "INC-1", "severity": "Sev3", "opened": "2027-03-08T10:00:00-05:00"},
            {"id": "INC-2", "severity": "Sev2", "opened": "2027-03-30T10:00:00-05:00"}]
        self.assertEqual(co.validate(self.plan), [])
        self.assertEqual(co.hypercare(self.plan, NOW + dt.timedelta(days=30))[0], "passed")
        self.plan["hypercare"]["incidents"].append(
            {"id": "INC-3", "severity": "Sev1", "opened": "2027-03-10T10:00:00-05:00"})
        status, detail = co.hypercare(self.plan, NOW + dt.timedelta(days=30))
        self.assertEqual(status, "breached")
        self.assertIn("INC-3 (Sev1)", detail)

    def test_failed_or_unevidenced_check(self):
        self.plan["hypercare"]["cutover_completed_at"] = "2027-03-07T06:00:00-05:00"
        self.plan["hypercare"]["checks"][1].update(status="failed", evidence="")
        self.assertTrue(any("without evidence" in e for e in co.validate(self.plan)))
        self.plan["hypercare"]["checks"][1]["evidence"] = "INC-9"
        self.assertEqual(co.validate(self.plan), [])
        self.assertEqual(co.hypercare(self.plan, NOW)[0], "breached")
        del self.plan["hypercare"]["checks"][0]
        self.assertTrue(any("deferred to hypercare" in e for e in co.validate(self.plan)))

    def test_hypercare_is_at_least_fourteen_days_and_incidents_are_well_formed(self):
        self.plan["hypercare"]["days"] = 7
        self.plan["hypercare"]["incidents"] = [{"id": "INC-1", "severity": "P1", "opened": "yesterday"}]
        errors = co.validate(self.plan)
        self.assertTrue(any("at least 14" in e for e in errors))
        self.assertTrue(any("'yesterday' is not an ISO-8601" in e for e in errors))
        self.assertTrue(any("severity" in e for e in errors))


    def _closed_plan_dir(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        return tmp, approve_everything(self.plan, tmp)

    def _rewrite(self, tmp, rel, change):
        path = os.path.join(tmp, rel)
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        change(data)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f)

    def test_approved_gate_without_evidence_stays_open(self):
        tmp, plan = self._closed_plan_dir()
        self.assertEqual(co.decision(plan, tmp), (True, []))
        self._rewrite(tmp, "docs/pas/messaging-decommission.json",
                      lambda d: next(g for g in d["gates"] if g["id"] == "G2").update(evidence=""))
        go, reasons = co.decision(plan, tmp)
        self.assertFalse(go)
        self.assertTrue(any("gates G2" in r for r in reasons), reasons)

    def test_extract_feeds_need_a_date_and_confirmation(self):
        tmp, plan = self._closed_plan_dir()
        self._rewrite(tmp, "docs/pas/extracts-cutover.json",
                      lambda d: d["feeds"][0].update(cutover_date="TBD"))
        go, reasons = co.decision(plan, tmp)
        self.assertFalse(go)
        self.assertTrue(any("feeds Claims" in r for r in reasons), reasons)
        self._rewrite(tmp, "docs/pas/extracts-cutover.json",
                      lambda d: d["feeds"][0].update(cutover_date="2027-01-01", status="pending"))
        self.assertFalse(co.decision(plan, tmp)[0])

    def test_end_of_freeze_must_require_exactly_the_blocking_smoke_tests(self):
        plan = copy.deepcopy(self.plan)
        gate = next(s for s in plan["steps"] if "requires_smoke" in s)
        gate["requires_smoke"].append("SM-05")
        self.assertTrue(any("requires_smoke" in e for e in co.validate(plan)))
        plan = copy.deepcopy(self.plan)
        del plan["smoke_tests"][0]["blocks_reopen"]
        self.assertTrue(any("blocks_reopen" in e for e in co.validate(plan)))


if __name__ == "__main__":
    unittest.main()

