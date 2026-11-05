"""Unit tests for the COBOL characterization harness (AB-520)."""
import unittest

import parity

PROGRAM = """       IDENTIFICATION DIVISION.
       PROGRAM-ID. DEMO.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-A                      PIC 9(03).
       01  WS-B                      PIC 9(03).
           EXEC SQL INCLUDE SQLCA END-EXEC.
       PROCEDURE DIVISION.
       0000-MAIN.
           IF WS-A NOT = DFHRESP(NORMAL)
               CONTINUE
           END-IF
           COMPUTE WS-A =
               WS-B / WS-A
           EXEC SQL
               SELECT X INTO :WS-A FROM T
           END-EXEC
           EXEC CICS RETURN END-EXEC.
"""

STUBS = """# demo
@stub 1 SQL SELECT X INTO :WS-A
           @get-num X WS-A
@end
@stub 2 CICS RETURN
           STOP RUN
@end
"""


class PrecompileTest(unittest.TestCase):
    def test_replaces_exec_blocks_and_adds_harness(self):
        out = parity.precompile(PROGRAM, STUBS)
        self.assertNotIn("EXEC SQL\n", out.replace("*>PX EXEC", ""))
        self.assertIn("COPY PXSQLCA.", out)
        self.assertIn("COPY PXWORK.", out)
        self.assertTrue(out.rstrip().endswith("COPY PXPROC."))
        self.assertIn("IF WS-A NOT = 0", out)
        self.assertIn("MOVE PX-NUM TO WS-A", out)
        # the period after END-EXEC is kept after the stub
        self.assertIn("           STOP RUN\n           .\n", out)

    def test_divide_guard_inserted_before_compute(self):
        out = parity.precompile(PROGRAM, STUBS).splitlines()
        guard = out.index("           IF WS-A = 0 PERFORM PX-ABEND-S0CB END-IF")
        self.assertTrue(out[guard + 1].strip().startswith("COMPUTE WS-A"))

    def test_fingerprint_mismatch_fails(self):
        with self.assertRaisesRegex(parity.HarnessError, "fingerprint"):
            parity.precompile(PROGRAM, STUBS.replace("SELECT X", "SELECT Y"))

    def test_stub_count_must_match(self):
        with self.assertRaisesRegex(parity.HarnessError, "left over"):
            parity.precompile(PROGRAM, STUBS + "@stub 3 SQL COMMIT\n CONTINUE\n@end\n")
        with self.assertRaisesRegex(parity.HarnessError, "no stub"):
            parity.precompile(PROGRAM, STUBS.split("@stub 2")[0])

    def test_stub_numbering_and_unknown_macro(self):
        with self.assertRaisesRegex(parity.HarnessError, "expected 1"):
            parity.parse_stubs("@stub 2 SQL X\n@end\n")
        with self.assertRaisesRegex(parity.HarnessError, "unknown stub macro"):
            parity.expand_macro("           @nope A B")
        with self.assertRaisesRegex(parity.HarnessError, "72 columns"):
            parity.expand_macro("           @text NAME WS-A-VERY-LONG-HOST-VARIABLE-NAME-THAT-OVERFLOWS-AREA-B")


class FixtureTest(unittest.TestCase):
    def test_rows_flattened(self):
        env = parity.fixture_env({"id": "c", "input": {"ROWS": [{"POLICY_TYPE": "AUT"}, {"POLICY_TYPE": "HOM"}],
                                                        "RESP": 0}})
        self.assertEqual(env, {"PX_ROWS": "2", "PX_R0001_POLICY_TYPE": "AUT", "PX_R0002_POLICY_TYPE": "HOM",
                               "PX_RESP": "0"})

    def test_floats_and_bad_keys_rejected(self):
        with self.assertRaisesRegex(parity.HarnessError, "no floats"):
            parity.fixture_env({"id": "c", "input": {"TOTAL_PREMIUM": 10.5}})
        with self.assertRaisesRegex(parity.HarnessError, "UPPER_SNAKE_CASE"):
            parity.fixture_env({"id": "c", "input": {"total": "1"}})


class OutputTest(unittest.TestCase):
    def test_parse_output(self):
        out = (b"PX|E|SQL INSERT X\nPX|C|-0000000000181.000000\nPX|T|NAME|AB  \n"
               b"PX|N|AMT|2| 0000000000012.340000\nPX|P|F|6| 0000000000000.501369\nlegacy display\n"
               b"PX|E|CICS SEND TEXT\nPX|T|TEXT|\x00\x01Z\n")
        effects, displays, abend = parity.parse_output(out)
        self.assertIsNone(abend)
        self.assertEqual(displays, ["legacy display"])
        self.assertEqual(effects[0], {"op": "SQL INSERT X", "sqlcode": -181, "text": {"NAME": "AB"},
                                      "numbers": {"AMT": "12.34"}, "probes": {"F": "0.501369"}})
        self.assertEqual(effects[1], {"op": "CICS SEND TEXT", "text": {"TEXT": "\\x00\\x01Z"}})

    def test_abend(self):
        _, _, abend = parity.parse_output(b"PX|A|S0CB\n")
        self.assertEqual(abend, "S0CB")


class KnownDifferenceTest(unittest.TestCase):
    QUIRKS = {"Q-01": {"id": "Q-01", "proposed_decision": "fix"},
              "Q-05": {"id": "Q-05", "proposed_decision": "preserve"}}
    SUITES = {"s": {"suite": "s", "_case_ids": {"a"}}}

    def entry(self, **override):
        base = {"id": "KD-01", "suite": "s", "cases": ["a"], "paths": ["*"], "quirk": "Q-01",
                "decision": "docs/modernization/quirk-register.md#q-01", "ticket": "AB-526",
                "sign_off": "pending", "reason": "r"}
        base.update(override)
        return {"differences": [base]}

    def test_valid_entry(self):
        self.assertEqual(parity.validate_known_differences(self.entry(), self.SUITES, self.QUIRKS), [])

    def test_only_fix_decisions_allowed(self):
        errors = parity.validate_known_differences(
            self.entry(quirk="Q-05", decision="docs/modernization/quirk-register.md#q-05"), self.SUITES, self.QUIRKS)
        self.assertTrue(any("only quirks the business decided to fix" in e for e in errors))

    def test_decision_link_required(self):
        errors = parity.validate_known_differences(self.entry(decision="somewhere"), self.SUITES, self.QUIRKS)
        self.assertTrue(any("must link the quirk decision" in e for e in errors))

    def test_unknown_case_and_sign_off(self):
        errors = parity.validate_known_differences(self.entry(cases=["b"], sign_off="yes"), self.SUITES, self.QUIRKS)
        self.assertTrue(any("unknown case" in e for e in errors))
        self.assertTrue(any("sign_off" in e for e in errors))


class RunSelectionTest(unittest.TestCase):
    def test_unknown_case_is_an_error(self):
        original = parity.cobc_path
        parity.cobc_path = lambda: "/bin/true"
        try:
            with self.assertRaisesRegex(parity.HarnessError, "no case no-such-case"):
                list(parity.run_suites(parity.load_registry(), "polrnw-renewal", "no-such-case"))
        finally:
            parity.cobc_path = original


class RepositoryTest(unittest.TestCase):
    def test_registry_fixtures_and_known_differences_are_valid(self):
        errors = parity.validate(parity.load_registry(), parity.load_json(parity.QUIRKS),
                                 parity.load_json(parity.KNOWN_DIFFERENCES))
        self.assertEqual(errors, [])

    def test_every_stub_file_precompiles(self):
        for suite in parity.load_registry()["suites"]:
            source = (parity.ROOT / suite["legacy_source"]).read_text(encoding="latin-1")
            stubs = (parity.ROOT / suite["stubs"]).read_text(encoding="utf-8")
            self.assertIn("COPY PXPROC.", parity.precompile(source, stubs, suite["stubs"]))


if __name__ == "__main__":
    unittest.main()
