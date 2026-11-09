import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

import support
import common


class GitHelpersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = support.make_repo(
            {"sql/a.sql": "base a\n", "sql/old.sql": "old\n", "docs/x.md": "x\n"},
            {"sql/a.sql": "head a\n", "sql/new.sql": "new\n"},
            removed=["sql/old.sql"],
        )
        self.addCleanup(self.tmp.cleanup)
        patcher = mock.patch.multiple(common, ROOT=self.tmp.name, BASE_REF="base")
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_merge_base_is_base_branch_tip(self):
        tip = support.run_git(self.tmp.name, "rev-parse", "base").strip()
        self.assertEqual(common.merge_base(), tip)

    def test_base_file_reads_merge_base_version(self):
        self.assertEqual(common.base_file("sql/a.sql"), "base a\n")
        self.assertEqual(common.base_file("sql/old.sql"), "old\n")

    def test_base_file_returns_none_for_file_added_on_head(self):
        self.assertIsNone(common.base_file("sql/new.sql"))

    def test_head_file_reads_working_tree(self):
        self.assertEqual(common.head_file("sql/a.sql"), "head a\n")
        self.assertIsNone(common.head_file("sql/old.sql"))

    def test_files_at_base_and_head_filter_by_prefix(self):
        self.assertEqual(sorted(common.files_at_base("sql/")), ["sql/a.sql", "sql/old.sql"])
        self.assertEqual(sorted(common.files_at_head("sql/")), ["sql/a.sql", "sql/new.sql"])
        self.assertIn("docs/x.md", common.files_at_base())

    def test_git_raises_on_failure_unless_check_disabled(self):
        with self.assertRaises(Exception):
            common.git("rev-parse", "no-such-ref")
        self.assertEqual(common.git("rev-parse", "--verify", "-q", "no-such-ref", check=False), "")


class AllowedIdsTest(unittest.TestCase):
    def test_missing_file_means_nothing_allowed(self):
        with mock.patch.object(common, "ALLOWED_FILE", "/nonexistent/allowed.json"):
            self.assertEqual(common.allowed_ids(), {})

    def test_entries_are_keyed_by_id(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump({"allowed": [{"id": "a", "ticket": "AB-1"}, {"id": "b", "ticket": "AB-2"}]}, fh)
        self.addCleanup(os.unlink, fh.name)
        with mock.patch.object(common, "ALLOWED_FILE", fh.name):
            ids = common.allowed_ids()
        self.assertEqual(set(ids), {"a", "b"})
        self.assertEqual(ids["b"]["ticket"], "AB-2")

    def test_file_without_allowed_key_is_empty(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            fh.write("{}")
        self.addCleanup(os.unlink, fh.name)
        with mock.patch.object(common, "ALLOWED_FILE", fh.name):
            self.assertEqual(common.allowed_ids(), {})

    def test_repo_allowed_changes_file_is_valid(self):
        for fid, entry in common.allowed_ids().items():
            self.assertEqual(entry["id"], fid)
            self.assertTrue(entry.get("ticket"), f"{fid} needs a ticket")


class ReportTest(unittest.TestCase):
    def run_report(self, findings, allowed=None, env=None):
        out = io.StringIO()
        with mock.patch.object(common, "allowed_ids", return_value=allowed or {}), \
                mock.patch.dict(os.environ, env or {}, clear=False), redirect_stdout(out):
            if not env:
                os.environ.pop("GITHUB_STEP_SUMMARY", None)
            code = common.report("My check", findings)
        return code, out.getvalue()

    def test_no_findings_passes(self):
        code, out = self.run_report([])
        self.assertEqual(code, 0)
        self.assertIn("== My check: PASS", out)
        self.assertIn("no compatibility-relevant changes found", out)

    def test_notes_only_pass(self):
        code, out = self.run_report([("NOTE", "n1", "additive")])
        self.assertEqual(code, 0)
        self.assertIn("NOTE      n1: additive", out)

    def test_fail_fails(self):
        code, out = self.run_report([("NOTE", "n1", "x"), ("FAIL", "f1", "broken")])
        self.assertEqual(code, 1)
        self.assertIn("== My check: FAIL", out)
        self.assertIn("FAIL      f1: broken", out)

    def test_allowed_fail_is_downgraded_to_approved(self):
        allowed = {"f1": {"id": "f1", "ticket": "AB-524", "reason": "auth added"}}
        code, out = self.run_report([("FAIL", "f1", "status 200 -> 401")], allowed)
        self.assertEqual(code, 0)
        self.assertIn("APPROVED  f1: status 200 -> 401 (ticket AB-524: auth added)", out)
        self.assertIn("PASS", out)

    def test_allowed_entry_without_ticket_shows_placeholder(self):
        code, out = self.run_report([("FAIL", "f1", "m")], {"f1": {"id": "f1"}})
        self.assertEqual(code, 0)
        self.assertIn("(ticket ?: )", out)

    def test_allowlist_only_covers_listed_ids(self):
        allowed = {"f1": {"id": "f1", "ticket": "T"}}
        code, out = self.run_report([("FAIL", "f1", "a"), ("FAIL", "f2", "b")], allowed)
        self.assertEqual(code, 1)
        self.assertIn("FAIL      f2: b", out)

    def test_writes_github_step_summary(self):
        with tempfile.TemporaryDirectory() as d:
            summary = os.path.join(d, "summary.md")
            code, _ = self.run_report([("FAIL", "f1", "m")], env={"GITHUB_STEP_SUMMARY": summary})
            with open(summary) as fh:
                text = fh.read()
        self.assertEqual(code, 1)
        self.assertIn("### My check: FAIL", text)
        self.assertIn("- `FAIL      f1: m`", text)

    def test_summary_for_empty_findings(self):
        with tempfile.TemporaryDirectory() as d:
            summary = os.path.join(d, "summary.md")
            self.run_report([], env={"GITHUB_STEP_SUMMARY": summary})
            with open(summary) as fh:
                self.assertIn("no compatibility-relevant changes found", fh.read())


class ExitWithTest(unittest.TestCase):
    def test_exit_with_raises_system_exit_with_code(self):
        with self.assertRaises(SystemExit) as cm:
            common.exit_with(3)
        self.assertEqual(cm.exception.code, 3)


if __name__ == "__main__":
    unittest.main()
