import copy
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

import mainframe_decommission as md


def shell_calls(run):
    """Calls that would remove the trees; the git ls-files scan for dependents is read-only."""
    return [c for c in run.call_args_list if list(c.args[0][:2]) == ["git", "rm"]]


class MainframeDecommissionTest(unittest.TestCase):
    def setUp(self):
        self.plan = md.fr.load(md.PLAN)

    def test_committed_plan_is_valid_and_fully_gated(self):
        self.assertEqual(md.validate(self.plan), [])
        self.assertEqual({g["status"] for g in self.plan["gates"]}, {"pending"})
        self.assertEqual({s["status"] for s in self.plan["signoffs"]}, {"pending"})
        self.assertIsNone(md.mips_saving(self.plan))

    def test_every_cics_transaction_and_ca7_job_is_listed(self):
        names = " ".join(i["name"] for i in self.plan["decommission"])
        for name in ("PNEW", "PRWL", "PEND", "PQRY", "PUWR", "PAS0050", "PAS0100", "PAS0200"):
            self.assertIn(name, names)

    def test_a_definition_that_moved_is_reported(self):
        plan = copy.deepcopy(self.plan)
        plan["decommission"][5]["evidence"] = "PAS9999"
        self.assertTrue(any(e.startswith("D6:") for e in md.validate(plan)))

    def test_every_ddl_table_needs_an_archive_entry(self):
        plan = copy.deepcopy(self.plan)
        plan["archive"]["tables"] = [t for t in plan["archive"]["tables"] if t["table"] != "PREMIUMS"]
        plan["archive"]["tables"].append({"table": "NOPE", "retention": "7y"})
        errors = md.validate(plan)
        self.assertTrue(any("table PREMIUMS" in e for e in errors))
        self.assertTrue(any("NOPE is not a table" in e for e in errors))

    def test_ddl_parser_reads_qualified_tables(self):
        with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as f:
            f.write("CREATE TABLESPACE X;\nCREATE TABLE ACMEINS.POLICIES (\n  A INT);\n create table other (b int);\n")
        try:
            self.assertEqual(md.ddl_tables(f.name), {"POLICIES", "other"})
        finally:
            os.unlink(f.name)

    def test_only_the_tree_removal_is_scripted(self):
        plan = copy.deepcopy(self.plan)
        plan["steps"][0]["command"] = "echo CEMT SET TRANSACTION(PNEW) DISABLED"
        self.assertTrue(any("only scripted step" in e for e in md.validate(plan)))
        plan = copy.deepcopy(self.plan)
        plan["repository"]["trees"] = ["cobol", "jcl", "java-facade", "sql"]
        errors = md.validate(plan)
        self.assertTrue(any("only scripted step" in e for e in errors))
        self.assertIn("repository: sql is both kept and removed", errors)

    def test_new_dependent_without_entry_is_reported(self):
        with tempfile.TemporaryDirectory() as root:
            for tree in ("cobol", "jcl", "java-facade"):
                os.makedirs(os.path.join(root, tree))
            os.makedirs(os.path.join(root, "pas"))
            with open(os.path.join(root, "pas", "Reader.java"), "w") as f:
                f.write('Path.of("cobol/copybooks")')
            with open(os.path.join(root, "notes.txt"), "w") as f:
                f.write("acme.cobol/ and myjcl/ are not references")
            files = ["pas/Reader.java", "notes.txt", "cobol/X.cbl"]
            self.assertEqual(md.dependents(root, ["cobol", "jcl", "java-facade"], files), ["pas/Reader.java"])
            self.assertEqual(md.uncovered(["pas/Reader.java"], [{"path": "pas/"}]), [])
            self.assertEqual(md.uncovered(["pas/Reader.java"], [{"path": "pas"}]), ["pas/Reader.java"])

    def test_committed_dependents_are_all_covered(self):
        found = md.dependents(md.ROOT, self.plan["repository"]["trees"])
        self.assertIn("pas/pas-domain/src/main/java/com/acme/insurance/pas/domain/copybook/CopybookLayouts.java", found)
        self.assertEqual(md.uncovered(found, self.plan["repository"]["dependents"]), [])

    def test_mips_saving_and_g7(self):
        plan = copy.deepcopy(self.plan)
        plan["mips"].update(baseline_msu=400, after_msu=310)
        self.assertEqual(md.mips_saving(plan), (90, 22.5))
        g7 = next(g for g in plan["gates"] if g["id"] == "G7")
        g7.update(status="approved", evidence="capacity report")
        self.assertEqual(md.validate(plan), [])
        plan["mips"]["after_msu"] = 420
        self.assertIn("G7: approved but mips shows no saving", md.validate(plan))
        plan["mips"]["after_msu"] = -1
        self.assertTrue(any("non-negative" in e for e in md.validate(plan)))

    def test_dry_run_changes_nothing(self):
        with mock.patch.object(md.subprocess, "run", wraps=md.subprocess.run) as run, redirect_stdout(io.StringIO()) as out:
            self.assertEqual(md.main([]), 0)
        self.assertEqual(shell_calls(run), [])
        self.assertIn("dry run, nothing changed", out.getvalue())

    def test_execute_refuses_while_gates_are_open(self):
        with mock.patch.object(md.fr, "current_branch", return_value="feature"), \
                mock.patch.object(md.subprocess, "run", wraps=md.subprocess.run) as run, \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as err:
            self.assertEqual(md.main(["--execute"]), 2)
        self.assertEqual(shell_calls(run), [])
        self.assertIn("gates not approved: G1", err.getvalue())

    def test_execute_refuses_on_protected_branch_even_when_approved(self):
        plan = copy.deepcopy(self.plan)
        for g in plan["gates"]:
            g.update(status="approved", evidence="x")
        self.assertEqual(md.fr.refusal(plan, "devin/ab-516-v3"), "refusing to run on protected branch devin/ab-516-v3")
        self.assertIsNone(md.fr.refusal(plan, "decommission"))


    def test_execute_runs_git_without_a_shell(self):
        plan = copy.deepcopy(self.plan)
        for g in plan["gates"]:
            g.update(status="approved", evidence="CHG-1")
        with mock.patch.object(md.fr, "load", return_value=plan), \
                mock.patch.object(md.fr, "current_branch", return_value="decommission/pas"), \
                mock.patch.object(md, "validate", return_value=[]), \
                mock.patch.object(md.subprocess, "run") as run, \
                mock.patch.object(md, "print_report"), redirect_stdout(io.StringIO()):
            self.assertEqual(md.main(["--execute"]), 0)
        self.assertEqual(shell_calls(run),
                         [mock.call(["git", "rm", "-r", "-q", "cobol", "jcl", "java-facade"], cwd=md.ROOT, check=True)])

    def test_tree_names_cannot_carry_shell_syntax(self):
        plan = copy.deepcopy(self.plan)
        plan["repository"]["trees"] = ["cobol;touch x"]
        self.assertTrue(any("plain top-level directory" in e for e in md.validate(plan)))


if __name__ == "__main__":
    unittest.main()
