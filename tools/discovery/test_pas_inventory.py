"""Unit tests for the PAS discovery inventory scanner (AB-517)."""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pas_inventory as inv  # noqa: E402


class SqlTablesTest(unittest.TestCase):
    def test_select_into_host_variables_is_read_only(self):
        sql = "SELECT CUST_ID INTO :CUST-ID FROM POLICY_HOLDERS WHERE CUST_ID = :X"
        self.assertEqual(inv.sql_tables(sql), {"POLICY_HOLDERS": {"R"}})

    def test_insert_update_and_join(self):
        self.assertEqual(inv.sql_tables("INSERT INTO PREMIUMS (A, B) VALUES (:A, :B)"), {"PREMIUMS": {"C"}})
        self.assertEqual(inv.sql_tables("UPDATE POLICIES SET X = 1 WHERE Y = :Y"), {"POLICIES": {"U"}})
        self.assertEqual(inv.sql_tables("SELECT P.A FROM POLICIES P JOIN POLICY_HOLDERS C ON P.X = C.Y"),
                         {"POLICIES": {"R"}, "POLICY_HOLDERS": {"R"}})

    def test_cursor_and_dummy_table(self):
        self.assertEqual(inv.sql_tables("DECLARE C1 CURSOR FOR SELECT A FROM TERRITORY_FACTORS"),
                         {"TERRITORY_FACTORS": {"R"}})
        self.assertEqual(inv.sql_tables("SELECT NEXT VALUE FOR POLICY_SEQ INTO :S FROM SYSIBM.SYSDUMMY1"), {})

    def test_non_dml_ignored(self):
        self.assertEqual(inv.sql_tables("INCLUDE SQLCA"), {})
        self.assertEqual(inv.sql_tables("OPEN TERR_CURSOR"), {})


class LegacyScanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.facts = inv.scan()

    def test_every_program_and_job_found(self):
        self.assertEqual(set(self.facts["programs"]), {"POLNEW", "POLRNW", "POLEND", "POLQRY", "UNDWRT", "PREMBAT"})
        self.assertEqual(set(self.facts["jobs"]), {"DAILY-EXTRACT", "MONTHLY-EXPOSURE", "PREMIUM-BATCH"})

    def test_transaction_ids(self):
        tx = {n: p["transid"] for n, p in self.facts["programs"].items()}
        self.assertEqual(tx, {"POLNEW": "PNEW", "POLRNW": "PRWL", "POLEND": "PEND",
                              "POLQRY": "PQRY", "UNDWRT": "PUWR", "PREMBAT": None})

    def test_crud_of_known_programs(self):
        p = self.facts["programs"]
        self.assertEqual(p["POLNEW"]["tables"], {"POLICIES": "C", "COVERAGES": "C", "POLICY_HOLDERS": "R"})
        self.assertEqual(p["POLRNW"]["tables"], {"POLICIES": "RU", "COVERAGES": "U", "CLAIMS": "R"})
        self.assertEqual(p["UNDWRT"]["tables"]["UNDERWRITING_DECISIONS"], "C")
        self.assertEqual(p["PREMBAT"]["tables"], {"POLICIES": "R", "PREMIUMS": "C", "TERRITORY_FACTORS": "R"})
        self.assertEqual(p["POLNEW"]["sequences"], ["POLICY_SEQ"])

    def test_queues_resolve_literal_names(self):
        p = self.facts["programs"]
        self.assertEqual(p["POLNEW"]["td_queues"], ["ACME.PAS.UNDERWRITING.REQUEST"])
        self.assertEqual(p["UNDWRT"]["td_queues"], ["ACME.PAS.UNDERWRITING.DECISION"])
        self.assertEqual(p["POLEND"]["ts_queues"], ["ENDORSEMENT-AUDIT"])

    def test_claims_is_external(self):
        self.assertEqual(self.facts["tables_not_in_ddl"], ["CLAIMS"])
        self.assertEqual(self.facts["ddl_tables_unreferenced"], [])

    def test_jcl_outputs_and_transfers(self):
        j = self.facts["jobs"]
        self.assertEqual({o["dd"]: o["lrecl"] for o in j["DAILY-EXTRACT"]["outputs"]}, {"POLOUT": 200, "COVOUT": 150})
        self.assertEqual([t["to"] for t in j["DAILY-EXTRACT"]["transfers"]],
                         ["/claims/import/policy_extract.dat", "/claims/import/coverage_extract.dat"])
        self.assertEqual(j["MONTHLY-EXPOSURE"]["transfers"][0]["host"], "ACTUARIAL.ACME.LOCAL")
        self.assertEqual([s["runs"] for s in j["PREMIUM-BATCH"]["steps"]], ["PREMBAT", None, None])
        self.assertEqual(j["PREMIUM-BATCH"]["steps"][2]["cond"], "(4,LT,STEP010)")

    def test_copybook_lengths_match_compat_gate(self):
        lengths = {n: c["record_length"] for n, c in self.facts["copybooks"].items()}
        self.assertEqual(lengths, {"POLICY-RECORD": 125, "COVERAGE-RECORD": 124,
                                   "CUSTOMER-RECORD": 365, "PREMIUM-RECORD": 105})


class QuirkRegisterTest(unittest.TestCase):
    def setUp(self):
        self.quirks = json.loads(inv.read(inv.QUIRKS))["quirks"]

    def test_register_is_valid(self):
        self.assertEqual(inv.validate_quirks(self.quirks), [])

    def test_known_quirks_from_ticket_are_first(self):
        titles = [q["title"].lower() for q in self.quirks[:4]]
        self.assertIn("leap-year", titles[0])
        self.assertIn("open-claims", titles[1])
        self.assertIn("territory", titles[2])
        self.assertIn("15%", titles[3])

    def test_validation_catches_bad_entries(self):
        bad = dict(self.quirks[0], id="Q-1", owner="", proposed_decision="maybe",
                   evidence=[{"file": "cobol/programs/POLRNW.cbl", "contains": "NO SUCH TEXT"}])
        errors = " ".join(inv.validate_quirks([bad, bad]))
        for expected in ("missing owner", "id must be", "duplicate id", "proposed_decision", "evidence not found"):
            self.assertIn(expected, errors)

    def test_other_ticket_quirk_ids_allowed(self):
        q = dict(self.quirks[0], id="Q-526-1")
        self.assertEqual(inv.validate_quirks([q]), [])


class GeneratedDocsTest(unittest.TestCase):
    def test_docs_are_current(self):
        self.assertEqual(inv.main([]), 0)


if __name__ == "__main__":
    unittest.main()
