import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import common  # noqa: E402


def run_report(findings, allowed=None, env=None):
    out = StringIO()
    with mock.patch.object(common, "allowed_ids", return_value=allowed or {}), \
            mock.patch.dict(os.environ, env or {}, clear=False), redirect_stdout(out):
        if not env:
            os.environ.pop("GITHUB_STEP_SUMMARY", None)
        code = common.report("Demo check", findings)
    return code, out.getvalue()


class ReportTests(unittest.TestCase):
    def test_no_findings_passes_with_placeholder_line(self):
        code, out = run_report([])
        self.assertEqual(code, 0)
        self.assertIn("== Demo check: PASS", out)
        self.assertIn("no compatibility-relevant changes found", out)

    def test_note_only_passes(self):
        code, out = run_report([("NOTE", "x:added", "additive")])
        self.assertEqual(code, 0)
        self.assertIn("NOTE      x:added: additive", out)

    def test_unapproved_fail_fails(self):
        code, out = run_report([("NOTE", "n", "ok"), ("FAIL", "x:dropped", "column dropped")])
        self.assertEqual(code, 1)
        self.assertIn("== Demo check: FAIL", out)
        self.assertIn("FAIL      x:dropped: column dropped", out)

    def test_allowed_fail_is_downgraded_to_approved(self):
        allowed = {"x:dropped": {"id": "x:dropped", "ticket": "AB-1", "reason": "migrated"}}
        code, out = run_report([("FAIL", "x:dropped", "column dropped")], allowed)
        self.assertEqual(code, 0)
        self.assertIn("APPROVED  x:dropped: column dropped (ticket AB-1: migrated)", out)

    def test_allowed_entry_without_ticket_shows_placeholder(self):
        code, out = run_report([("FAIL", "x", "m")], {"x": {"id": "x"}})
        self.assertEqual(code, 0)
        self.assertIn("(ticket ?: )", out)

    def test_allowed_id_does_not_affect_other_fails(self):
        allowed = {"a": {"id": "a", "ticket": "T"}}
        code, _ = run_report([("FAIL", "a", "m"), ("FAIL", "b", "m")], allowed)
        self.assertEqual(code, 1)

    def test_writes_github_step_summary(self):
        with tempfile.TemporaryDirectory() as d:
            summary = os.path.join(d, "summary.md")
            code, _ = run_report([("FAIL", "x", "boom")], env={"GITHUB_STEP_SUMMARY": summary})
            with open(summary) as fh:
                text = fh.read()
        self.assertEqual(code, 1)
        self.assertIn("### Demo check: FAIL", text)
        self.assertIn("- `FAIL      x: boom`", text)

    def test_step_summary_placeholder_when_empty(self):
        with tempfile.TemporaryDirectory() as d:
            summary = os.path.join(d, "summary.md")
            run_report([], env={"GITHUB_STEP_SUMMARY": summary})
            with open(summary) as fh:
                text = fh.read()
        self.assertIn("### Demo check: PASS", text)
        self.assertIn("no compatibility-relevant changes found", text)


class AllowedIdsTests(unittest.TestCase):
    def test_missing_file_returns_empty(self):
        with mock.patch.object(common, "ALLOWED_FILE", "/nonexistent/allowed.json"):
            self.assertEqual(common.allowed_ids(), {})

    def test_indexes_entries_by_id(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump({"allowed": [{"id": "a", "ticket": "AB-1"}, {"id": "b", "ticket": "AB-2"}]}, fh)
        try:
            with mock.patch.object(common, "ALLOWED_FILE", fh.name):
                ids = common.allowed_ids()
        finally:
            os.unlink(fh.name)
        self.assertEqual(set(ids), {"a", "b"})
        self.assertEqual(ids["b"]["ticket"], "AB-2")

    def test_file_without_allowed_key_returns_empty(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump({}, fh)
        try:
            with mock.patch.object(common, "ALLOWED_FILE", fh.name):
                self.assertEqual(common.allowed_ids(), {})
        finally:
            os.unlink(fh.name)

    def test_repo_allowlist_is_valid(self):
        self.assertIsInstance(common.allowed_ids(), dict)


class GitHelpersTests(unittest.TestCase):
    """Runs the git helpers against a throwaway repository."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@e", GIT_COMMITTER_NAME="t",
                   GIT_COMMITTER_EMAIL="t@e")
        self.env = env

        def g(*args):
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, env=env)
        self.g = g
        g("init", "-q", "-b", "base")
        self.write("sql/a.sql", "v1")
        self.write("other.txt", "x")
        g("add", ".")
        g("commit", "-q", "-m", "base")
        g("checkout", "-q", "-b", "feature")
        self.write("sql/a.sql", "v2")
        self.write("sql/b.sql", "new")
        g("add", ".")
        g("commit", "-q", "-m", "feature")
        self.patches = [mock.patch.object(common, "ROOT", self.root), mock.patch.object(common, "BASE_REF", "base")]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in self.patches:
            p.stop()
        self.tmp.cleanup()

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as fh:
            fh.write(text)

    def test_base_file_reads_merge_base_version(self):
        self.assertEqual(common.base_file("sql/a.sql"), "v1")

    def test_base_file_none_when_absent_at_base(self):
        self.assertIsNone(common.base_file("sql/b.sql"))

    def test_head_file_reads_working_tree(self):
        self.assertEqual(common.head_file("sql/a.sql"), "v2")
        self.assertIsNone(common.head_file("sql/missing.sql"))

    def test_files_at_base_and_head_filter_by_prefix(self):
        self.assertEqual(common.files_at_base("sql/"), ["sql/a.sql"])
        self.assertEqual(sorted(common.files_at_head("sql/")), ["sql/a.sql", "sql/b.sql"])
        self.assertIn("other.txt", common.files_at_base())

    def test_git_check_raises_on_failure(self):
        with self.assertRaises(subprocess.CalledProcessError):
            common.git("show", "nope:nothing")
        self.assertEqual(common.git("show", "nope:nothing", check=False), "")

    def test_exit_with_exits_with_code(self):
        with self.assertRaises(SystemExit) as cm:
            common.exit_with(3)
        self.assertEqual(cm.exception.code, 3)


if __name__ == "__main__":
    unittest.main()
