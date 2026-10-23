import tempfile
import unittest
from decimal import Decimal
from pathlib import Path
import hashlib

from harness import (
    check_expect_legacy,
    cobol_name,
    decode_field,
    decode_fixed_records,
    encode_field,
    get_path,
    replace_exec_blocks,
    replace_rewrites,
    source_lock_paths,
)


class HarnessFieldTests(unittest.TestCase):
    def test_path_and_cobol_names_preserve_nested_field_boundaries(self):
        value = {"currentDate": "20241015", "policy": {"policyNumber": "POLICY1"}}
        self.assertEqual(get_path(value, "currentDate"), "20241015")
        self.assertEqual(get_path(value, "policy.policyNumber"), "POLICY1")
        self.assertEqual(cobol_name("PX-IN", "policy.policyNumber"), "PX-IN-POLICY-POLICY-NUMBER")

    def test_fixed_width_values_preserve_sign_and_scale(self):
        decimal = {"name": "amount", "type": "decimal", "integerDigits": 5, "scale": 2}
        integer = {"name": "score", "type": "int", "digits": 3}
        self.assertEqual(encode_field(Decimal("-12.30"), decimal), "-0001230")
        self.assertEqual(decode_field("-0001230", decimal), Decimal("-12.30"))
        self.assertEqual(decode_field("-005", integer), -5)

    def test_fixed_records_preserve_non_ascii_bytes(self):
        self.assertEqual(decode_fixed_records(b"A\x90  Z\x00  ", 4), ["A\x90", "Z\x00"])

    def test_rejects_out_of_range_and_excess_scale(self):
        field = {"name": "amount", "type": "decimal", "integerDigits": 2, "scale": 2}
        with self.assertRaises(ValueError):
            encode_field(Decimal("100.00"), field)
        with self.assertRaises(ValueError):
            encode_field(Decimal("1.001"), field)

    def test_exec_matching_count_mismatch_is_an_error(self):
        procedure = "EXEC SQL SELECT 1 END-EXEC"
        with self.assertRaisesRegex(ValueError, "expected 2"):
            replace_exec_blocks(
                procedure,
                [{"name": "select", "pattern": "SELECT 1", "expectCount": 2}],
                "TEST",
            )

    def test_environment_rewrite_redirects_the_report_file(self):
        self.assertEqual(
            replace_rewrites(
                "SELECT PREMIUM-REPORT ASSIGN TO PREMRPT",
                [
                    {
                        "pattern": "ASSIGN TO PREMRPT",
                        "replacement": "ASSIGN TO DYNAMIC PX-REPORT-PATH",
                        "expectCount": 1,
                    }
                ],
                "PREMBAT",
            ),
            "SELECT PREMIUM-REPORT ASSIGN TO DYNAMIC PX-REPORT-PATH",
        )

    def test_decimal_golden_expectation_is_scale_independent(self):
        check_expect_legacy(
            {"outputs": {"amount": Decimal("1.00")}},
            {"expectLegacy": {"outputs.amount": Decimal("1.0")}},
            Path("fixture.json"),
        )

    def test_source_drift_is_detectable_with_a_temporary_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.cbl"
            source.write_text("PROGRAM-ID. TEST.\n", encoding="utf-8")
            before = source_lock_paths([source], root)
            source.write_text(source.read_text(encoding="utf-8") + "      * drift\n", encoding="utf-8")
            after = source_lock_paths([source], root)
            self.assertNotEqual(before, after)
            self.assertEqual(
                after.split()[0], hashlib.sha256(source.read_bytes()).hexdigest()
            )


if __name__ == "__main__":
    unittest.main()
