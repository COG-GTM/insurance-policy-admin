import copy
import io
import json
import os
import tempfile
import unittest
from unittest import mock

import messaging_decommission as md


class MessagingDecommissionTest(unittest.TestCase):
    def setUp(self):
        self.plan = md.fr.load(md.PLAN)

    def test_committed_plan_is_valid_and_every_gate_is_pending(self):
        self.assertEqual(md.validate(self.plan), [])
        self.assertEqual({g["status"] for g in self.plan["gates"]}, {"pending"})

    def test_old_queue_definitions_are_listed(self):
        names = {i["name"] for i in self.plan["decommission"]}
        for queue in ("ACME", "ACME.PAS.UNDERWRITING.REQUEST", "ACME.PAS.UNDERWRITING.DECISION",
                      "ENDORSEMENT-AUDIT"):
            self.assertIn(queue, names)
        self.assertTrue(any(i["kind"] == "SQL Server linked server" for i in self.plan["decommission"]))

    def test_a_definition_that_moved_is_reported(self):
        plan = copy.deepcopy(self.plan)
        plan["decommission"][1]["evidence"] = "ACME.PAS.NO.SUCH.QUEUE"
        self.assertTrue(any("D2:" in e for e in md.validate(plan)))

    def test_steps_stay_manual(self):
        plan = copy.deepcopy(self.plan)
        plan["steps"][0]["command"] = "git rm -r cobol"
        self.assertTrue(any("manual" in e for e in md.validate(plan)))

    def test_sink_configs_cover_every_bridge_topic_with_raw_bytes(self):
        self.assertEqual(len(md.bridge_topics()), 3)
        self.assertEqual(md.check_sinks(), [])

    def test_sink_check_rejects_literal_credentials_and_json_bodies(self):
        with tempfile.TemporaryDirectory() as d:
            config = {"topics": "pas.bridge.x", "value.converter": "x", "mq.message.body.jms": "true",
                      "mq.password": "literal"}
            with open(os.path.join(d, "mq-sink-x.json"), "w") as f:
                json.dump({"config": config}, f)
            errors = md.check_sinks(d, {"pas.bridge.x", "pas.bridge.y"})
        self.assertEqual(len(errors), 4)

    def test_dry_run_lists_definitions_and_execute_refuses_while_gates_are_open(self):
        with mock.patch("sys.stdout", new=io.StringIO()) as out:
            self.assertEqual(md.main([]), 0)
        self.assertIn("ACME.PAS.UNDERWRITING.REQUEST", out.getvalue())
        self.assertIn("dry run", out.getvalue())
        with mock.patch.object(md.fr, "current_branch", return_value="feature"), \
                mock.patch("sys.stdout", new=io.StringIO()), \
                mock.patch("sys.stderr", new=io.StringIO()) as err:
            self.assertEqual(md.main(["--execute"]), 2)
        self.assertIn("gates not approved: G1", err.getvalue())


if __name__ == "__main__":
    unittest.main()
