import json
import os
import subprocess
import tempfile
import unittest
from unittest import mock

import support  # noqa: F401  (puts compat/ on sys.path)

import common


class AllowedIdsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "allowed-changes.json")
        patcher = mock.patch.object(common, "ALLOWED_FILE", self.path)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, data):
        with open(self.path, "w") as fh:
            json.dump(data, fh)

    def test_missing_file_allows_nothing(self):
        self.assertEqual(common.allowed_ids(), {})

    def test_entries_are_keyed_by_id(self):
        entry = {"id": "api:GET /x:status", "ticket": "AB-524", "reason": "auth"}
        self.write({"allowed": [entry]})
        self.assertEqual(common.allowed_ids(), {"api:GET /x:status": entry})

    def test_file_without_allowed_key_allows_nothing(self):
        self.write({})
        self.assertEqual(common.allowed_ids(), {})

    def test_repository_allow_list_is_valid(self):
        with mock.patch.object(common, "ALLOWED_FILE", os.path.join(common.ROOT, "compat", "allowed-changes.json")):
            for fid, entry in common.allowed_ids().items():
                self.assertTrue(entry.get("ticket"), fid)


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        env = mock.patch.dict(os.environ, {}, clear=False)
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("GITHUB_STEP_SUMMARY", None)
        self.allowed = {}
        patcher = mock.patch.object(common, "allowed_ids", side_effect=lambda: self.allowed)
        patcher.start()
        self.addCleanup(patcher.stop)

    def report(self, findings):
        with mock.patch("builtins.print") as p:
            code = common.report("Check", findings)
        return code, [c.args[0] for c in p.call_args_list]

    def test_no_findings_pass(self):
        code, lines = self.report([])
        self.assertEqual(code, 0)
        self.assertEqual(lines, ["== Check: PASS", "  no compatibility-relevant changes found"])

    def test_notes_do_not_fail(self):
        code, lines = self.report([("NOTE", "n1", "additive")])
        self.assertEqual(code, 0)
        self.assertEqual(lines[0], "== Check: PASS")
        self.assertEqual(lines[1], "  NOTE      n1: additive")

    def test_any_fail_fails(self):
        code, lines = self.report([("NOTE", "n1", "ok"), ("FAIL", "f1", "broken")])
        self.assertEqual(code, 1)
        self.assertEqual(lines[0], "== Check: FAIL")
        self.assertIn("  FAIL      f1: broken", lines)

    def test_allowed_fail_is_downgraded_to_approved(self):
        self.allowed = {"f1": {"id": "f1", "ticket": "AB-524", "reason": "auth added"}}
        code, lines = self.report([("FAIL", "f1", "401")])
        self.assertEqual(code, 0)
        self.assertEqual(lines, ["== Check: PASS", "  APPROVED  f1: 401 (ticket AB-524: auth added)"])

    def test_allow_list_does_not_hide_other_fails(self):
        self.allowed = {"f1": {"id": "f1"}}
        code, lines = self.report([("FAIL", "f1", "a"), ("FAIL", "f2", "b")])
        self.assertEqual(code, 1)
        self.assertIn("  APPROVED  f1: a (ticket ?: )", lines)

    def test_writes_github_step_summary(self):
        summary = os.path.join(self.tmp.name, "summary.md")
        os.environ["GITHUB_STEP_SUMMARY"] = summary
        self.report([("FAIL", "f1", "broken")])
        self.report([])
        with open(summary) as fh:
            text = fh.read()
        self.assertEqual(text, "### Check: FAIL\n- `FAIL      f1: broken`\n\n"
                               "### Check: PASS\n- `no compatibility-relevant changes found`\n\n")


class GitHelperTests(unittest.TestCase):
    """Exercise the git helpers against a throwaway repository."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        repo = self.tmp.name

        def run(*args):
            subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True)

        run("init", "-q", "-b", "main")
        run("config", "user.email", "t@example.com")
        run("config", "user.name", "t")
        os.makedirs(os.path.join(repo, "sql"))
        with open(os.path.join(repo, "sql", "a.sql"), "w") as fh:
            fh.write("base\n")
        run("add", ".")
        run("commit", "-q", "-m", "base")
        run("branch", "base")
        with open(os.path.join(repo, "sql", "a.sql"), "w") as fh:
            fh.write("head\n")
        with open(os.path.join(repo, "new.txt"), "w") as fh:
            fh.write("n\n")
        run("add", ".")
        run("commit", "-q", "-m", "head")
        for name, value in (("ROOT", repo), ("BASE_REF", "base")):
            p = mock.patch.object(common, name, value)
            p.start()
            self.addCleanup(p.stop)
        self.repo = repo

    def test_merge_base_is_base_commit(self):
        expected = subprocess.check_output(["git", "rev-parse", "base"], cwd=self.repo, text=True).strip()
        self.assertEqual(common.merge_base(), expected)

    def test_base_and_head_file_contents(self):
        self.assertEqual(common.base_file("sql/a.sql"), "base\n")
        self.assertEqual(common.head_file("sql/a.sql"), "head\n")

    def test_missing_files_are_none(self):
        self.assertIsNone(common.base_file("new.txt"))
        self.assertIsNone(common.head_file("missing.txt"))

    def test_file_listings_filter_by_prefix(self):
        self.assertEqual(common.files_at_base(), ["sql/a.sql"])
        self.assertEqual(sorted(common.files_at_head()), ["new.txt", "sql/a.sql"])
        self.assertEqual(common.files_at_head("sql/"), ["sql/a.sql"])

    def test_git_raises_on_failure_unless_unchecked(self):
        with self.assertRaises(subprocess.CalledProcessError):
            common.git("show", "nope:nope")
        self.assertEqual(common.git("show", "nope:nope", check=False), "")


class ExitWithTests(unittest.TestCase):
    def test_exits_with_code(self):
        with self.assertRaises(SystemExit) as cm:
            common.exit_with(3)
        self.assertEqual(cm.exception.code, 3)


if __name__ == "__main__":
    unittest.main()
