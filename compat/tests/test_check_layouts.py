import os
import unittest
from unittest import mock

import check_layouts
from check_layouts import jcl_lrecls, parse_copybook, pic_size

from tests import support
from tests.support import cobol

RECORD = cobol(
    "*----------------------------------------------------------------",
    "01  TEST-RECORD.",
    "    05  REC-ID                 PIC X(12).",
    "    05  REC-TYPE               PIC X(03).",
    "        88  REC-TYPE-AUTO      VALUE 'AUT'.",
    "    05  REC-PREMIUM            PIC S9(09)V99 COMP-3.",
    "    05  REC-COUNT              PIC 9(03).",
    "    05  FILLER                 PIC X(10).",
)

JCL = """//PASJOB   JOB (ACCT),'TEST',CLASS=A
//STEP01   EXEC PGM=IKJEFT01
//SYSPRINT DD SYSOUT=*
//POLOUT   DD DSN=ACME.PAS.EXTRACT.POLICY.DAILY,
//            DISP=(NEW,CATLG,DELETE),
//            DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//COVOUT   DD DSN=ACME.PAS.EXTRACT.COVERAGE.DAILY,DCB=(RECFM=FB,LRECL=150)
"""


class PicSizeTests(unittest.TestCase):
    def test_display_alphanumeric_and_numeric(self):
        self.assertEqual(pic_size("X(10)", "DISPLAY"), 10)
        self.assertEqual(pic_size("9(08)", "DISPLAY"), 8)
        self.assertEqual(pic_size("xx", "DISPLAY"), 2)

    def test_display_sign_and_implied_decimal_take_no_storage(self):
        self.assertEqual(pic_size("S9(5)V99", "DISPLAY"), 7)

    def test_edited_picture_counts_every_position(self):
        self.assertEqual(pic_size("ZZ,ZZ9.99", "DISPLAY"), 9)

    def test_packed_decimal_is_half_the_digits_plus_sign_rounded_up(self):
        self.assertEqual(pic_size("S9(09)V99", "COMP-3"), 6)
        self.assertEqual(pic_size("S9(07)V99", "COMP-3"), 5)
        self.assertEqual(pic_size("S9(03)V9999", "PACKED-DECIMAL"), 4)

    def test_binary_uses_halfword_fullword_doubleword(self):
        self.assertEqual(pic_size("S9(4)", "COMP"), 2)
        self.assertEqual(pic_size("9(5)", "BINARY"), 4)
        self.assertEqual(pic_size("S9(9)", "COMP-4"), 4)
        self.assertEqual(pic_size("S9(18)", "COMP-5"), 8)


class ParseCopybookTests(unittest.TestCase):
    def test_offsets_sizes_and_total_length(self):
        fields, total = parse_copybook(RECORD)
        self.assertEqual(fields["REC-ID"], (0, 12, "X(12) DISPLAY"))
        self.assertEqual(fields["REC-TYPE"][:2], (12, 3))
        self.assertEqual(fields["REC-PREMIUM"], (15, 6, "S9(09)V99 COMP-3"))
        self.assertEqual(fields["REC-COUNT"][:2], (21, 3))
        self.assertEqual(total, 34)

    def test_level_88_group_items_and_comments_are_not_fields(self):
        fields, _ = parse_copybook(RECORD)
        self.assertNotIn("REC-TYPE-AUTO", fields)
        self.assertNotIn("TEST-RECORD", fields)
        self.assertEqual(len(fields), 5)

    def test_fillers_are_numbered(self):
        fields, total = parse_copybook(
            cobol(
                "01  R.", "05  FILLER PIC X(2).", "05  A PIC X.", "05  FILLER PIC X(3)."
            )
        )
        self.assertEqual(fields["FILLER#1"][:2], (0, 2))
        self.assertEqual(fields["FILLER#2"][:2], (3, 3))
        self.assertEqual(total, 6)

    def test_occurs_multiplies_field_size(self):
        fields, total = parse_copybook(
            cobol(
                "01  R.",
                "05  AMTS PIC S9(5)V99 COMP-3 OCCURS 12 TIMES.",
                "05  B PIC X.",
            )
        )
        self.assertEqual(fields["AMTS"][:2], (0, 48))
        self.assertEqual(fields["B"][:2], (48, 1))
        self.assertEqual(total, 49)

    def test_statement_continued_over_lines_and_picture_is_keyword(self):
        fields, _ = parse_copybook(
            cobol(
                "01  R.",
                "05  LONG-NAME-FIELD",
                "        PICTURE IS X(20).",
                "05  NEXT PIC 9(2).",
            )
        )
        self.assertEqual(fields["LONG-NAME-FIELD"][:2], (0, 20))
        self.assertEqual(fields["NEXT"][:2], (20, 2))

    def test_text_beyond_column_72_is_ignored(self):
        line = "       05  A PIC X(4).".ljust(72) + "SEQ00010"
        fields, total = parse_copybook("       01  R.\n" + line + "\n")
        self.assertEqual((fields["A"][:2], total), ((0, 4), 4))

    def test_field_after_redefines_resumes_at_original_offset(self):
        fields, _ = parse_copybook(
            cobol(
                "01  R.",
                "05  A PIC X(10).",
                "05  B REDEFINES A PIC 9(10).",
                "05  C PIC X(5).",
            )
        )
        self.assertEqual(fields["C"][:2], (10, 5))

    def test_redefines_as_last_item_does_not_extend_earlier_fields(self):
        fields, _ = parse_copybook(
            cobol(
                "01  R.",
                "05  A PIC X(4).",
                "05  B PIC X(6).",
                "05  B-ALT REDEFINES B PIC 9(6).",
            )
        )
        self.assertEqual(fields["A"][:2], (0, 4))
        self.assertEqual(fields["B"][:2], (4, 6))

    def test_empty_or_missing_copybook(self):
        self.assertEqual(parse_copybook(""), ({}, 0))
        self.assertEqual(parse_copybook(None), ({}, 0))

    def test_repository_policy_record_layout(self):
        with open(
            os.path.join(support.REPO_ROOT, "cobol", "copybooks", "POLICY-RECORD.cpy")
        ) as fh:
            fields, total = parse_copybook(fh.read())
        self.assertEqual(fields["POLICY-NUMBER"][:2], (0, 12))
        self.assertEqual(fields["POLICY-TOTAL-PREMIUM"][1], 6)
        self.assertEqual(total, 125)


class JclLreclTests(unittest.TestCase):
    def test_lrecl_on_continuation_line_belongs_to_preceding_dd(self):
        self.assertEqual(jcl_lrecls(JCL), {"POLOUT": 200, "COVOUT": 150})

    def test_lrecl_before_any_dd_is_ignored_and_none_is_empty(self):
        self.assertEqual(jcl_lrecls("//* LRECL=80 in a comment\n//SYSIN DD *\n"), {})
        self.assertEqual(jcl_lrecls(None), {})


class MainTests(unittest.TestCase):
    CPY = "cobol/copybooks/TEST-RECORD.cpy"
    JCL_PATH = "jcl/DAILY.jcl"

    def run_main(self, base, head):
        cap = support.FindingsCapture()
        with (
            mock.patch.object(
                check_layouts, "files_at_base", lambda prefix="": list(base)
            ),
            mock.patch.object(check_layouts, "base_file", base.get),
            mock.patch.object(check_layouts, "head_file", head.get),
            mock.patch.object(check_layouts, "report", cap),
        ):
            rc = check_layouts.main()
        return rc, cap

    def test_unchanged_layouts_pass(self):
        files = {self.CPY: RECORD, self.JCL_PATH: JCL, "README.md": "x"}
        rc, cap = self.run_main(files, files)
        self.assertEqual((rc, cap.findings), (0, []))
        self.assertEqual(cap.check_name, "Record & file layout compatibility")

    def test_removed_copybook_fails(self):
        rc, cap = self.run_main({self.CPY: RECORD}, {})
        self.assertEqual(rc, 1)
        self.assertEqual(cap.ids(), [f"layout:{self.CPY}:removed"])

    def test_field_carved_out_of_filler_is_compatible(self):
        head = RECORD.replace(
            "05  FILLER                 PIC X(10).",
            "05  REC-API-FLAG PIC X(01).\n           05  FILLER PIC X(09).",
        )
        rc, cap = self.run_main({self.CPY: RECORD}, {self.CPY: head})
        self.assertEqual(rc, 0)
        self.assertEqual(cap.ids(), [f"layout:{self.CPY}:REC-API-FLAG:added"])
        self.assertEqual(cap.findings[0][0], "NOTE")
        self.assertIn("offset 24", cap.findings[0][2])

    def test_widened_field_moves_later_fields_and_changes_record_length(self):
        head = RECORD.replace("PIC X(12)", "PIC X(15)")
        rc, cap = self.run_main({self.CPY: RECORD}, {self.CPY: head})
        self.assertEqual(rc, 1)
        self.assertEqual(
            cap.ids(),
            [
                f"layout:{self.CPY}:record-length",
                f"layout:{self.CPY}:REC-ID:moved",
                f"layout:{self.CPY}:REC-TYPE:moved",
                f"layout:{self.CPY}:REC-PREMIUM:moved",
                f"layout:{self.CPY}:REC-COUNT:moved",
            ],
        )
        self.assertIn("record length 34 -> 37 bytes", cap.findings[0][2])
        self.assertIn("offset/size 0/12 -> 0/15", cap.findings[1][2])

    def test_renamed_field_fails_as_removed(self):
        head = RECORD.replace("REC-COUNT ", "REC-CNT   ")
        rc, cap = self.run_main({self.CPY: RECORD}, {self.CPY: head})
        self.assertEqual(rc, 1)
        self.assertEqual(cap.ids("FAIL"), [f"layout:{self.CPY}:REC-COUNT:removed"])
        self.assertEqual(cap.ids("NOTE"), [f"layout:{self.CPY}:REC-CNT:added"])

    def test_usage_change_with_same_size_is_compatible(self):
        head = RECORD.replace("PIC 9(03).", "PIC X(03).")
        rc, cap = self.run_main({self.CPY: RECORD}, {self.CPY: head})
        self.assertEqual((rc, cap.findings), (0, []))

    def test_removed_jcl_fails(self):
        rc, cap = self.run_main({self.JCL_PATH: JCL}, {})
        self.assertEqual(rc, 1)
        self.assertEqual(cap.ids(), [f"layout:{self.JCL_PATH}:removed"])

    def test_jcl_lrecl_change_and_removed_dataset_fail(self):
        head = JCL.replace("LRECL=200", "LRECL=250").replace(
            "//COVOUT   DD", "//COVOUT2  DD"
        )
        rc, cap = self.run_main({self.JCL_PATH: JCL}, {self.JCL_PATH: head})
        self.assertEqual(rc, 1)
        self.assertEqual(
            cap.ids(),
            [
                f"layout:{self.JCL_PATH}:POLOUT:lrecl",
                f"layout:{self.JCL_PATH}:COVOUT:dataset-removed",
            ],
        )
        self.assertEqual(cap.findings[0][2], "LRECL 200 -> 250")

    def test_jcl_new_dataset_is_not_reported(self):
        head = JCL + "//NEWOUT   DD DSN=X,DCB=(LRECL=80)\n"
        rc, cap = self.run_main({self.JCL_PATH: JCL}, {self.JCL_PATH: head})
        self.assertEqual((rc, cap.findings), (0, []))


if __name__ == "__main__":
    unittest.main()
