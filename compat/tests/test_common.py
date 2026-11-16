import json
import os
import subprocess
import tempfile
import unittest
from unittest import mock

from helpers import allowed_changes, run_quiet

import common


class ReportTests(unittest.TestCase):
    def test_pass_with_no_findings(self):
        with allowed_changes():
            code, out = run_quiet(common.report, "Check", [])
        self.assertEqual(code, 0)
        self.assertEqual(out, "== Check: PASS\n  no compatibility-relevant changes found\n")

    def test_notes_do_not_fail(self):
        with allowed_changes():
            code, out = run_quiet(common.report, "Check", [("NOTE", "n1", "info")])
        self.assertEqual(code, 0)
        self.assertIn("  NOTE      n1: info", out)

    def test_fail_and_approved_findings(self):
        allowed = [{"id": "f1", "ticket": "AB-1", "reason": "ok"}, {"id": "n1"}]
        with allowed_changes(allowed):
            code, out = run_quiet(common.report, "Check", [("FAIL", "f1", "a"), ("NOTE", "n1", "b"), ("FAIL", "f2", "c")])
        self.assertEqual(code, 1)
        self.assertEqual(
            out.splitlines(), ["== Check: FAIL", "  APPROVED  f1: a (ticket AB-1: ok)", "  NOTE      n1: b", "  FAIL      f2: c"]
        )

    def test_only_approved_failures_pass(self):
        with allowed_changes([{"id": "f1"}]):
            code, out = run_quiet(common.report, "Check", [("FAIL", "f1", "a")])
        self.assertEqual(code, 0)
        self.assertIn("APPROVED  f1: a (ticket ?: )", out)

    def test_writes_github_step_summary(self):
        with allowed_changes(), tempfile.TemporaryDirectory() as d:
            summary = os.path.join(d, "summary.md")
            with mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": summary}):
                run_quiet(common.report, "One", [("FAIL", "f", "m")])
                run_quiet(common.report, "Two", [])
            with open(summary) as fh:
                text = fh.read()
        self.assertEqual(
            text, "### One: FAIL\n- `FAIL      f: m`\n\n### Two: PASS\n- `no compatibility-relevant changes found`\n\n"
        )


class AllowedIdsTests(unittest.TestCase):
    def test_missing_file_is_empty(self):
        with mock.patch.object(common, "ALLOWED_FILE", "/nonexistent/allowed.json"):
            self.assertEqual(common.allowed_ids(), {})

    def test_indexes_entries_by_id(self):
        with allowed_changes([{"id": "a", "ticket": "T"}]):
            self.assertEqual(common.allowed_ids(), {"a": {"id": "a", "ticket": "T"}})

    def test_repo_allow_list_is_valid_json(self):
        with open(os.path.join(common.ROOT, "compat", "allowed-changes.json")) as fh:
            data = json.load(fh)
        self.assertIsInstance(data["allowed"], list)
        self.assertTrue(all("id" in e and e.get("ticket") for e in data["allowed"]))


class GitHelperTests(unittest.TestCase):
    """Exercise the git accessors against a throwaway two-commit repository."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = tmp.name
        env = {
            "GIT_AUTHOR_NAME": "t",
            "GIT_AUTHOR_EMAIL": "t@example.com",
            "GIT_COMMITTER_NAME": "t",
            "GIT_COMMITTER_EMAIL": "t@example.com",
        }

        def g(*args):
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, env={**os.environ, **env})

        g("init", "-q", "-b", "main")
        self.write("sql/a.sql", "base a")
        self.write("sql/gone.sql", "gone")
        g("add", ".")
        g("commit", "-q", "-m", "base")
        g("branch", "base")
        g("rm", "-q", "sql/gone.sql")
        self.write("sql/a.sql", "head a")
        self.write("jcl/new.jcl", "new")
        g("add", ".")
        g("commit", "-q", "-m", "head")
        for name, value in (("ROOT", self.root), ("BASE_REF", "base")):
            p = mock.patch.object(common, name, value)
            p.start()
            self.addCleanup(p.stop)

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as fh:
            fh.write(text)

    def test_merge_base_is_base_commit(self):
        self.assertEqual(common.merge_base(), common.git("rev-parse", "base").strip())

    def test_base_and_head_file_contents(self):
        self.assertEqual(common.base_file("sql/a.sql"), "base a")
        self.assertEqual(common.head_file("sql/a.sql"), "head a")
        self.assertEqual(common.base_file("sql/gone.sql"), "gone")
        self.assertIsNone(common.head_file("sql/gone.sql"))
        self.assertIsNone(common.base_file("jcl/new.jcl"))

    def test_file_listings_with_prefix(self):
        self.assertEqual(common.files_at_base(), ["sql/a.sql", "sql/gone.sql"])
        self.assertEqual(common.files_at_head("sql/"), ["sql/a.sql"])
        self.assertEqual(common.files_at_head(), ["jcl/new.jcl", "sql/a.sql"])

    def test_git_check_flag(self):
        with self.assertRaises(subprocess.CalledProcessError):
            common.git("show", "nope:missing")
        self.assertEqual(common.git("show", "nope:missing", check=False), "")

    def test_exit_with(self):
        with self.assertRaises(SystemExit) as cm:
            common.exit_with(3)
        self.assertEqual(cm.exception.code, 3)


if __name__ == "__main__":
    unittest.main()
