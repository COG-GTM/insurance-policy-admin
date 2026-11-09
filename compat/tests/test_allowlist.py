"""Exceptions in allowed-changes.json only apply once reviewed onto the base branch."""
import json
import os
import shutil
import subprocess
import tempfile
import unittest

COMPAT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Split so the security check does not flag this file's own fixtures.
SECRET_LINE = "db." + "password" + "=Sup3rS3cretValue\n"
SECRET_ID = "security:hardcoded-" + "secret" + ":java-facade/src/main/resources/application.properties"
PROPS = "java-facade/src/main/resources/application.properties"


class AllowlistTest(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.repo)
        shutil.copytree(COMPAT, os.path.join(self.repo, "compat"),
                        ignore=shutil.ignore_patterns("tests", "__pycache__", "api"))
        self._write(PROPS, "server.port=8080\n")
        self._write("compat/allowed-changes.json", json.dumps({"allowed": []}))
        self._git("init", "-q", "-b", "main")
        self._commit("base")
        self._git("update-ref", "refs/remotes/origin/main", "HEAD")
        self._git("checkout", "-q", "-b", "pr")

    def _git(self, *args):
        env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t",
                   GIT_COMMITTER_EMAIL="t@t")
        return subprocess.run(["git", *args], cwd=self.repo, env=env, check=True,
                              capture_output=True, text=True).stdout

    def _write(self, path, text, mode="w"):
        full = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, mode) as fh:
            fh.write(text)

    def _commit(self, msg):
        self._git("add", "-A")
        self._git("commit", "-q", "-m", msg)

    def _allow(self, *entries):
        self._write("compat/allowed-changes.json", json.dumps({"allowed": list(entries)}))

    def _merge_to_base(self):
        self._git("update-ref", "refs/remotes/origin/main", "HEAD")

    def _add_secret(self):
        self._write(PROPS, SECRET_LINE, mode="a")
        self._commit("add secret")

    def _security(self):
        env = dict(os.environ, COMPAT_BASE_REF="origin/main")
        env.pop("GITHUB_STEP_SUMMARY", None)
        r = subprocess.run(["python3", "compat/check_security.py"], cwd=self.repo, env=env,
                           capture_output=True, text=True)
        return r.returncode, r.stdout

    def test_secret_fails_without_exception(self):
        self._add_secret()
        code, out = self._security()
        self.assertEqual(code, 1, out)
        self.assertIn(f"FAIL      {SECRET_ID}", out)

    def test_exception_added_in_same_pr_is_pending_and_still_fails(self):
        self._allow({"id": SECRET_ID, "ticket": "AB-999", "reason": "self-approved",
                     "approvers": ["mallory", "mallory-alt"]})
        self._add_secret()
        code, out = self._security()
        self.assertEqual(code, 1, out)
        self.assertNotIn("APPROVED", out)
        self.assertIn(f"PENDING   {SECRET_ID}", out)

    def test_id_only_exception_from_pr_still_fails(self):
        self._allow({"id": SECRET_ID})
        self._add_secret()
        code, out = self._security()
        self.assertEqual(code, 1, out)
        self.assertNotIn("APPROVED", out)

    def test_security_exception_on_base_needs_two_approvers(self):
        self._allow({"id": SECRET_ID, "ticket": "AB-524", "reason": "rotated test credential",
                     "approvers": ["alice", "@Alice"]})
        self._commit("exception with one distinct approver")
        self._merge_to_base()
        self._add_secret()
        code, out = self._security()
        self.assertEqual(code, 1, out)
        self.assertIn("security exceptions need 2 distinct approvers", out)

    def test_security_exception_on_base_without_ticket_is_ignored(self):
        self._allow({"id": SECRET_ID, "reason": "no ticket", "approvers": ["alice", "bob"]})
        self._commit("exception without ticket")
        self._merge_to_base()
        self._add_secret()
        code, out = self._security()
        self.assertEqual(code, 1, out)
        self.assertIn("ticket must look like AB-524", out)

    def test_reviewed_security_exception_on_base_approves(self):
        self._allow({"id": SECRET_ID, "ticket": "AB-524", "reason": "rotated test credential",
                     "approvers": ["alice", "bob"]})
        self._commit("reviewed exception")
        self._merge_to_base()
        self._add_secret()
        code, out = self._security()
        self.assertEqual(code, 0, out)
        self.assertIn(f"APPROVED  {SECRET_ID}", out)
        self.assertIn("approved by alice, bob on origin/main", out)


if __name__ == "__main__":
    unittest.main()
