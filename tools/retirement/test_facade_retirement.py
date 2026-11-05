import copy
import io
import unittest
from unittest import mock

import facade_retirement as fr


class FacadeRetirementTest(unittest.TestCase):
    def setUp(self):
        self.plan = fr.load()

    def test_committed_plan_is_valid_and_every_gate_is_still_pending(self):
        self.assertEqual(fr.validate(self.plan), [])
        self.assertEqual({g["status"] for g in self.plan["gates"]}, {"pending"})

    def test_dry_run_changes_nothing(self):
        with mock.patch("subprocess.run") as run, mock.patch("sys.stdout", new=io.StringIO()) as out:
            self.assertEqual(fr.main([]), 0)
        run.assert_not_called()
        self.assertIn("dry run", out.getvalue())
        self.assertIn("git rm -r -q java-facade", out.getvalue())

    def test_execute_refuses_while_gates_are_open(self):
        with mock.patch.object(fr, "current_branch", return_value="feature"), \
                mock.patch("subprocess.run") as run, mock.patch("sys.stdout", new=io.StringIO()), \
                mock.patch("sys.stderr", new=io.StringIO()) as err:
            self.assertEqual(fr.main(["--execute"]), 2)
        run.assert_not_called()
        self.assertIn("gates not approved: G1", err.getvalue())

    def test_execute_refuses_on_protected_branches_even_when_approved(self):
        plan = self.approved()
        for branch in ("main", "devin/ab-516-v3"):
            self.assertIn("protected branch", fr.refusal(plan, branch))
        self.assertIsNone(fr.refusal(plan, "devin/facade-retirement"))

    def test_approval_needs_evidence_and_known_status(self):
        plan = copy.deepcopy(self.plan)
        plan["gates"][0]["status"] = "approved"
        plan["gates"][1]["status"] = "done"
        errors = fr.validate(plan)
        self.assertTrue(any("G1: approved without evidence" in e for e in errors))
        self.assertTrue(any(e.startswith("G2: status") for e in errors))

    def approved(self):
        plan = copy.deepcopy(self.plan)
        for g in plan["gates"]:
            g["status"], g["evidence"] = "approved", "ticket link"
        return plan


if __name__ == "__main__":
    unittest.main()
