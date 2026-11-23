import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

import common

GIT_ENV = {
    "GIT_AUTHOR_NAME": "t",
    "GIT_AUTHOR_EMAIL": "t@example.com",
    "GIT_COMMITTER_NAME": "t",
    "GIT_COMMITTER_EMAIL": "t@example.com",
}


class ReportTests(unittest.TestCase):
    def run_report(self, findings, allowed=None, env=None):
        out = io.StringIO()
        with (
            mock.patch.object(common, "allowed_ids", lambda: allowed or {}),
            mock.patch.dict(os.environ, env or {}),
            redirect_stdout(out),
        ):
            if not env:
                os.environ.pop("GITHUB_STEP_SUMMARY", None)
            rc = common.report("Check", findings)
        return rc, out.getvalue()

    def test_no_findings_passes_with_placeholder_line(self):
        rc, out = self.run_report([])
        self.assertEqual(rc, 0)
        self.assertEqual(
            out, "== Check: PASS\n  no compatibility-relevant changes found\n"
        )

    def test_notes_do_not_fail(self):
        rc, out = self.run_report([("NOTE", "x:added", "additive")])
        self.assertEqual(rc, 0)
        self.assertIn("  NOTE      x:added: additive", out)

    def test_unapproved_fail_fails(self):
        rc, out = self.run_report([("FAIL", "x:removed", "gone"), ("NOTE", "y", "ok")])
        self.assertEqual(rc, 1)
        self.assertTrue(out.startswith("== Check: FAIL\n"))
        self.assertIn("  FAIL      x:removed: gone", out)

    def test_allowed_fail_is_downgraded_to_approved(self):
        allowed = {
            "x:removed": {
                "id": "x:removed",
                "ticket": "AB-524",
                "reason": "consumers migrated",
            }
        }
        rc, out = self.run_report([("FAIL", "x:removed", "gone")], allowed)
        self.assertEqual(rc, 0)
        self.assertIn(
            "APPROVED  x:removed: gone (ticket AB-524: consumers migrated)", out
        )

    def test_allowed_entry_without_ticket_shows_placeholder(self):
        rc, out = self.run_report([("FAIL", "x", "m")], {"x": {"id": "x"}})
        self.assertEqual(rc, 0)
        self.assertIn("(ticket ?: )", out)

    def test_allowlist_only_applies_to_matching_ids(self):
        rc, _ = self.run_report(
            [("FAIL", "x", "m"), ("FAIL", "z", "m")], {"x": {"id": "x", "ticket": "T"}}
        )
        self.assertEqual(rc, 1)

    def test_writes_github_step_summary(self):
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as fh:
            fh.write("existing\n")
        self.addCleanup(os.unlink, fh.name)
        rc, _ = self.run_report(
            [("FAIL", "x", "m")], env={"GITHUB_STEP_SUMMARY": fh.name}
        )
        self.assertEqual(rc, 1)
        with open(fh.name) as f:
            self.assertEqual(
                f.read(), "existing\n### Check: FAIL\n- `FAIL      x: m`\n\n"
            )


class AllowedIdsTests(unittest.TestCase):
    def test_missing_file_means_nothing_is_allowed(self):
        with mock.patch.object(common, "ALLOWED_FILE", "/nonexistent/allowed.json"):
            self.assertEqual(common.allowed_ids(), {})

    def test_entries_are_indexed_by_id(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump(
                {
                    "allowed": [
                        {"id": "a", "ticket": "AB-1"},
                        {"id": "b", "ticket": "AB-2"},
                    ]
                },
                fh,
            )
        self.addCleanup(os.unlink, fh.name)
        with mock.patch.object(common, "ALLOWED_FILE", fh.name):
            self.assertEqual(
                common.allowed_ids(),
                {
                    "a": {"id": "a", "ticket": "AB-1"},
                    "b": {"id": "b", "ticket": "AB-2"},
                },
            )

    def test_file_without_allowed_key_is_empty(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            fh.write("{}")
        self.addCleanup(os.unlink, fh.name)
        with mock.patch.object(common, "ALLOWED_FILE", fh.name):
            self.assertEqual(common.allowed_ids(), {})

    def test_repository_allowlist_is_valid(self):
        self.assertIsInstance(common.allowed_ids(), dict)


class GitHelperTests(unittest.TestCase):
    """Exercise the git helpers against a throwaway repository."""

    @classmethod
    def setUpClass(cls):
        cls.repo = tempfile.mkdtemp()

        def run(*args):
            subprocess.run(
                ["git", *args],
                cwd=cls.repo,
                check=True,
                capture_output=True,
                env={**os.environ, **GIT_ENV},
            )

        run("init", "-q", "-b", "main")
        os.makedirs(os.path.join(cls.repo, "sql"))
        with open(os.path.join(cls.repo, "sql", "a.sql"), "w") as fh:
            fh.write("CREATE TABLE A (X INT);\n")
        with open(os.path.join(cls.repo, "gone.txt"), "w") as fh:
            fh.write("bye\n")
        run("add", "-A")
        run("commit", "-q", "-m", "base")
        cls.base_sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=cls.repo, text=True
        ).strip()
        run("checkout", "-q", "-b", "feature")
        with open(os.path.join(cls.repo, "sql", "a.sql"), "w") as fh:
            fh.write("CREATE TABLE A (X INT, Y INT);\n")
        with open(os.path.join(cls.repo, "sql", "b.sql"), "w") as fh:
            fh.write("CREATE TABLE B (Z INT);\n")
        run("rm", "-q", "gone.txt")
        run("add", "-A")
        run("commit", "-q", "-m", "feature")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.repo)

    def setUp(self):
        for name, value in (("ROOT", self.repo), ("BASE_REF", "main")):
            p = mock.patch.object(common, name, value)
            p.start()
            self.addCleanup(p.stop)

    def test_merge_base_is_base_branch_commit(self):
        self.assertEqual(common.merge_base(), self.base_sha)

    def test_base_file_reads_merge_base_version_or_none(self):
        self.assertEqual(common.base_file("sql/a.sql"), "CREATE TABLE A (X INT);\n")
        self.assertEqual(common.base_file("gone.txt"), "bye\n")
        self.assertIsNone(common.base_file("sql/b.sql"))

    def test_head_file_reads_working_tree_or_none(self):
        self.assertEqual(
            common.head_file("sql/a.sql"), "CREATE TABLE A (X INT, Y INT);\n"
        )
        self.assertIsNone(common.head_file("gone.txt"))

    def test_file_listings_filter_by_prefix(self):
        self.assertEqual(common.files_at_base(), ["gone.txt", "sql/a.sql"])
        self.assertEqual(common.files_at_base("sql/"), ["sql/a.sql"])
        self.assertEqual(common.files_at_head("sql/"), ["sql/a.sql", "sql/b.sql"])

    def test_git_raises_on_failure_unless_check_disabled(self):
        with self.assertRaises(subprocess.CalledProcessError):
            common.git("rev-parse", "no-such-ref")
        self.assertEqual(
            common.git("rev-parse", "no-such-ref", check=False).strip(), "no-such-ref"
        )


class ExitWithTests(unittest.TestCase):
    def test_exits_with_given_code(self):
        with self.assertRaises(SystemExit) as cm:
            common.exit_with(3)
        self.assertEqual(cm.exception.code, 3)


if __name__ == "__main__":
    unittest.main()
