import os
import unittest
from unittest import mock

from support import COMPAT_DIR, captured_findings, ids

import check_layouts


def copybook(*lines):
    """Build fixed-format copybook text: sequence area (cols 1-6) + indicator + code."""
    return "\n".join(("000100" + line) if line.startswith("*") else ("       " + line) for line in lines) + "\n"


BASE_CPY = copybook(
    "*  POLICY TEST RECORD",
    "01  TEST-RECORD.",
    "    05  REC-NUMBER        PIC X(12).",
    "    05  REC-STATUS        PIC X(02).",
    "        88  REC-ACTIVE    VALUE 'AC'.",
    "    05  REC-PREMIUM       PIC S9(09)V99 COMP-3.",
    "    05  REC-COUNT         PIC S9(04) COMP.",
    "    05  FILLER            PIC X(10).",
)

BASE_JCL = """//EXTRACT  JOB (ACCT),'DAILY'
//STEP01   EXEC PGM=POLEXTR
//POLOUT   DD DSN=PAS.POLICY.EXTRACT,
//         DISP=(NEW,CATLG),
//         DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//COVOUT   DD DSN=PAS.COVERAGE.EXTRACT,
//         DCB=(RECFM=FB,LRECL=150,BLKSIZE=0)
//SYSOUT   DD SYSOUT=*
"""


class PicSizeTests(unittest.TestCase):
    def test_display_alphanumeric_and_numeric(self):
        self.assertEqual(check_layouts.pic_size("X(12)", "DISPLAY"), 12)
        self.assertEqual(check_layouts.pic_size("9(08)", "DISPLAY"), 8)
        self.assertEqual(check_layouts.pic_size("XXX", "DISPLAY"), 3)

    def test_display_sign_and_implied_decimal_take_no_storage(self):
        self.assertEqual(check_layouts.pic_size("S9(07)V99", "DISPLAY"), 9)
        self.assertEqual(check_layouts.pic_size("s9v99", "DISPLAY"), 3)

    def test_packed_decimal_is_half_the_digits_plus_sign_nibble(self):
        self.assertEqual(check_layouts.pic_size("S9(09)V99", "COMP-3"), 6)
        self.assertEqual(check_layouts.pic_size("S9(07)V99", "COMP-3"), 5)
        self.assertEqual(check_layouts.pic_size("S9(11)V99", "PACKED-DECIMAL"), 7)

    def test_binary_sizes_by_digit_count(self):
        self.assertEqual(check_layouts.pic_size("S9(04)", "COMP"), 2)
        self.assertEqual(check_layouts.pic_size("9(05)", "BINARY"), 4)
        self.assertEqual(check_layouts.pic_size("S9(09)", "COMP-4"), 4)
        self.assertEqual(check_layouts.pic_size("S9(10)", "COMP-5"), 8)


class ParseCopybookTests(unittest.TestCase):
    def test_offsets_sizes_and_total_length(self):
        fields, total = check_layouts.parse_copybook(BASE_CPY)
        self.assertEqual(fields["REC-NUMBER"], (0, 12, "X(12) DISPLAY"))
        self.assertEqual(fields["REC-STATUS"], (12, 2, "X(02) DISPLAY"))
        self.assertEqual(fields["REC-PREMIUM"], (14, 6, "S9(09)V99 COMP-3"))
        self.assertEqual(fields["REC-COUNT"], (20, 2, "S9(04) COMP"))
        self.assertEqual(fields["FILLER#1"], (22, 10, "X(10) DISPLAY"))
        self.assertEqual(total, 32)

    def test_group_items_comments_and_88_levels_are_not_fields(self):
        fields, _ = check_layouts.parse_copybook(BASE_CPY)
        self.assertNotIn("TEST-RECORD", fields)
        self.assertNotIn("REC-ACTIVE", fields)
        self.assertNotIn("POLICY", " ".join(fields))

    def test_statement_continued_over_several_lines(self):
        fields, total = check_layouts.parse_copybook(copybook(
            "01  R.",
            "    05  LONG-NAME-FIELD",
            "            PIC X(20).",
        ))
        self.assertEqual(fields["LONG-NAME-FIELD"], (0, 20, "X(20) DISPLAY"))
        self.assertEqual(total, 20)

    def test_occurs_multiplies_size(self):
        fields, total = check_layouts.parse_copybook(copybook(
            "01  R.",
            "    05  MONTHLY-AMT  PIC 9(05) OCCURS 12 TIMES.",
            "    05  TRAILER      PIC X(01).",
        ))
        self.assertEqual(fields["MONTHLY-AMT"][:2], (0, 60))
        self.assertEqual(fields["TRAILER"][:2], (60, 1))
        self.assertEqual(total, 61)

    def test_multiple_fillers_get_distinct_names(self):
        fields, _ = check_layouts.parse_copybook(copybook(
            "01  R.",
            "    05  FILLER  PIC X(02).",
            "    05  A       PIC X(01).",
            "    05  FILLER  PIC X(03).",
        ))
        self.assertEqual(fields["FILLER#1"][:2], (0, 2))
        self.assertEqual(fields["FILLER#2"][:2], (3, 3))

    def test_field_after_redefines_resumes_at_original_offset(self):
        fields, _ = check_layouts.parse_copybook(copybook(
            "01  R.",
            "    05  A               PIC X(04).",
            "    05  B REDEFINES A   PIC 9(04).",
            "    05  C               PIC X(02).",
        ))
        self.assertEqual(fields["C"][:2], (4, 2))

    def test_picture_is_keyword_variant(self):
        fields, _ = check_layouts.parse_copybook(copybook("01  R.", "    05  A  PICTURE IS X(07)."))
        self.assertEqual(fields["A"][:2], (0, 7))

    def test_empty_or_missing_text(self):
        self.assertEqual(check_layouts.parse_copybook(None), ({}, 0))
        self.assertEqual(check_layouts.parse_copybook(""), ({}, 0))

    def test_repository_policy_record_length(self):
        with open(os.path.join(COMPAT_DIR, "..", "cobol", "copybooks", "POLICY-RECORD.cpy")) as fh:
            fields, total = check_layouts.parse_copybook(fh.read())
        self.assertEqual(fields["POLICY-NUMBER"][:2], (0, 12))
        self.assertEqual(fields["POLICY-TOTAL-PREMIUM"][1], 6)
        self.assertEqual(total, 125)


class JclLreclTests(unittest.TestCase):
    def test_lrecl_is_attributed_to_enclosing_dd(self):
        self.assertEqual(check_layouts.jcl_lrecls(BASE_JCL), {"POLOUT": 200, "COVOUT": 150})

    def test_lrecl_before_any_dd_is_ignored(self):
        self.assertEqual(check_layouts.jcl_lrecls("//* LRECL=80 in a comment\n//OUT DD DCB=(LRECL=90)\n"), {"OUT": 90})

    def test_none_text(self):
        self.assertEqual(check_layouts.jcl_lrecls(None), {})


class MainTests(unittest.TestCase):
    CPY = "cobol/copybooks/TEST-RECORD.cpy"
    JCL = "jcl/DAILY-EXTRACT.jcl"

    def run_main(self, base_files, head_files):
        with mock.patch.object(check_layouts, "files_at_base", side_effect=lambda prefix="": list(base_files)), \
                mock.patch.object(check_layouts, "base_file", side_effect=base_files.get), \
                mock.patch.object(check_layouts, "head_file", side_effect=head_files.get), \
                captured_findings(check_layouts) as findings:
            code = check_layouts.main()
        return code, findings

    def run_cpy(self, new_text):
        return self.run_main({self.CPY: BASE_CPY}, {self.CPY: new_text})

    def run_jcl(self, new_text):
        return self.run_main({self.JCL: BASE_JCL}, {self.JCL: new_text})

    def test_unchanged_files_pass(self):
        code, findings = self.run_main({self.CPY: BASE_CPY, self.JCL: BASE_JCL}, {self.CPY: BASE_CPY, self.JCL: BASE_JCL})
        self.assertEqual((code, findings), (0, []))

    def test_widened_field_fails_on_length_and_shifted_fields(self):
        code, findings = self.run_cpy(BASE_CPY.replace("REC-STATUS        PIC X(02)", "REC-STATUS        PIC X(03)"))
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {
            f"layout:{self.CPY}:record-length",
            f"layout:{self.CPY}:REC-STATUS:moved",
            f"layout:{self.CPY}:REC-PREMIUM:moved",
            f"layout:{self.CPY}:REC-COUNT:moved",
        })
        length = next(m for _, fid, m in findings if fid.endswith("record-length"))
        self.assertEqual(length, "record length 32 -> 33 bytes")

    def test_usage_change_is_detected_as_size_change(self):
        _, findings = self.run_cpy(BASE_CPY.replace("PIC S9(09)V99 COMP-3", "PIC S9(09)V99"))
        moved = next(m for _, fid, m in findings if fid.endswith("REC-PREMIUM:moved"))
        self.assertIn("14/6 -> 14/11", moved)

    def test_renamed_field_fails_as_removed(self):
        code, findings = self.run_cpy(BASE_CPY.replace("REC-COUNT ", "REC-CNT   "))
        self.assertEqual(code, 1)
        self.assertIn(f"layout:{self.CPY}:REC-COUNT:removed", ids(findings, "FAIL"))
        self.assertIn(f"layout:{self.CPY}:REC-CNT:added", ids(findings, "NOTE"))

    def test_new_field_carved_from_filler_is_compatible(self):
        new = BASE_CPY.replace(
            "    05  FILLER            PIC X(10).",
            "    05  REC-API-FLAG      PIC X(01).\n           05  FILLER            PIC X(09).",
        )
        code, findings = self.run_cpy(new)
        self.assertEqual(code, 0)
        self.assertEqual(findings, [("NOTE", f"layout:{self.CPY}:REC-API-FLAG:added", "new field at offset 22")])

    def test_removed_copybook_fails(self):
        code, findings = self.run_main({self.CPY: BASE_CPY}, {})
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {f"layout:{self.CPY}:removed"})

    def test_lrecl_change_fails(self):
        code, findings = self.run_jcl(BASE_JCL.replace("LRECL=200", "LRECL=250"))
        self.assertEqual(code, 1)
        self.assertEqual(findings, [("FAIL", f"layout:{self.JCL}:POLOUT:lrecl", "LRECL 200 -> 250")])

    def test_removed_output_dataset_fails(self):
        new = BASE_JCL.replace("//COVOUT   DD DSN=PAS.COVERAGE.EXTRACT,\n//         DCB=(RECFM=FB,LRECL=150,BLKSIZE=0)\n", "")
        _, findings = self.run_jcl(new)
        self.assertEqual(ids(findings), {f"layout:{self.JCL}:COVOUT:dataset-removed"})

    def test_new_output_dataset_passes(self):
        code, findings = self.run_jcl(BASE_JCL + "//NEWOUT   DD DCB=(RECFM=FB,LRECL=80)\n")
        self.assertEqual((code, findings), (0, []))

    def test_removed_jcl_fails(self):
        code, findings = self.run_main({self.JCL: BASE_JCL}, {})
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {f"layout:{self.JCL}:removed"})

    def test_other_files_are_ignored(self):
        code, findings = self.run_main({"README.md": "x", "cobol/programs/POLNEW.cbl": "y"}, {})
        self.assertEqual((code, findings), (0, []))


if __name__ == "__main__":
    unittest.main()
