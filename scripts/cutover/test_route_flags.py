"""Unit tests for route_flags.py (run: python3 -m unittest discover -s scripts/cutover)."""
import contextlib
import io
import json
import pathlib
import shutil
import tempfile
import unittest

import route_flags as rf


class RouteFlagsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.file = str(pathlib.Path(self.dir) / "flags.json")
        shutil.copy(rf.DEFAULT, self.file)

    def tearDown(self):
        shutil.rmtree(self.dir)

    def run_cli(self, *args):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return rf.main([*args, "--file", self.file])

    def states(self):
        return {n: c["state"] for n, c in rf.load(self.file)["capabilities"].items()}

    def test_committed_file_is_valid_under_production_rules(self):
        self.assertEqual(0, rf.main(["validate", "--file", str(rf.DEFAULT), "--production"]))

    def test_committed_file_is_all_legacy_until_cutover(self):
        caps = rf.load(rf.DEFAULT)["capabilities"]
        self.assertEqual({"legacy"}, {c["state"] for c in caps.values()})

    def test_single_write_capability_cannot_go_new_alone(self):
        self.assertEqual(2, self.run_cli("set", "new", "new-business"))
        self.assertEqual("legacy", self.states()["new-business"])

    def test_all_merged_flip_then_full_rollback(self):
        self.assertEqual(0, self.run_cli("set", "--all-merged", "new"))
        s = self.states()
        self.assertEqual("new", s["new-business"])
        self.assertEqual("legacy", s["renewal"])
        self.assertEqual(0, self.run_cli("set", "--all", "legacy"))
        self.assertEqual({"legacy"}, set(self.states().values()))

    def test_production_rules_refuse_pending_merge_capabilities(self):
        self.assertEqual(2, self.run_cli("set", "--all", "new", "--production"))
        self.assertEqual(0, self.run_cli("set", "--all-merged", "new"))
        self.assertEqual(2, self.run_cli("validate", "--production"))

    def test_read_capability_moves_on_its_own(self):
        self.assertEqual(0, self.run_cli("set", "new", "inquiry"))

    def test_retired_requires_group_new(self):
        self.assertEqual(2, self.run_cli("set", "retired", "inquiry"))

    def test_unknown_state_and_capability_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            rf.main(["set", "live", "inquiry", "--file", self.file])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            rf.main(["set", "new", "billing", "--file", self.file])

    def test_capability_set_must_match_adr(self):
        doc = rf.load(self.file)
        del doc["capabilities"]["document-events"]
        pathlib.Path(self.file).write_text(json.dumps(doc))
        self.assertEqual(2, self.run_cli("validate"))


if __name__ == "__main__":
    unittest.main()
