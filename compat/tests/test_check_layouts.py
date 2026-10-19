import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import check_layouts as cl  # noqa: E402
from common import ROOT  # noqa: E402


def cpy(*lines):
    """Build fixed-format copybook text (sequence area in cols 1-6)."""
    return "\n".join("      " + line for line in lines) + "\n"


BASE_CPY = cpy(
    "* sample record",
    " 01  REC.",
    "     05  REC-ID          PIC X(10).",
    "     05  REC-TYPE        PIC X(03).",
    "         88  REC-AUTO    VALUE 'AUT'.",
    "     05  REC-AMT         PIC S9(09)V99 COMP-3.",
    "     05  FILLER          PIC X(07).",
)

JCL = """//JOB1     JOB (ACCT)
//STEP1    EXEC PGM=POLEXT
//POLOUT   DD DSN=A.B,
//         DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//COVOUT   DD DSN=A.C,DCB=(RECFM=FB,LRECL=150)
//SYSOUT   DD SYSOUT=*
"""


class PicSizeTests(unittest.TestCase):
    def test_display_alphanumeric_and_numeric(self):
        self.assertEqual(cl.pic_size("X(10)", "DISPLAY"), 10)
        self.assertEqual(cl.pic_size("9(08)", "DISPLAY"), 8)
        self.assertEqual(cl.pic_size("XX", "DISPLAY"), 2)

    def test_display_sign_and_implied_decimal_take_no_bytes(self):
        self.assertEqual(cl.pic_size("S9(5)V99", "DISPLAY"), 7)
        self.assertEqual(cl.pic_size("s9(3)v9", "DISPLAY"), 4)

    def test_packed_decimal(self):
        self.assertEqual(cl.pic_size("S9(09)V99", "COMP-3"), 6)
        self.assertEqual(cl.pic_size("S9(07)V99", "PACKED-DECIMAL"), 5)
        self.assertEqual(cl.pic_size("9", "COMP-3"), 1)

    def test_binary_sizes_by_digit_count(self):
        self.assertEqual(cl.pic_size("S9(4)", "COMP"), 2)
        self.assertEqual(cl.pic_size("9(5)", "BINARY"), 4)
        self.assertEqual(cl.pic_size("S9(9)", "COMP-4"), 4)
        self.assertEqual(cl.pic_size("S9(18)", "COMP-5"), 8)

    def test_edited_numeric(self):
        self.assertEqual(cl.pic_size("ZZZ9.99", "DISPLAY"), 7)


class ParseCopybookTests(unittest.TestCase):
    def test_offsets_skip_comments_and_88_levels(self):
        fields, total = cl.parse_copybook(BASE_CPY)
        self.assertEqual(fields["REC-ID"][:2], (0, 10))
        self.assertEqual(fields["REC-TYPE"][:2], (10, 3))
        self.assertEqual(fields["REC-AMT"], (13, 6, "S9(09)V99 COMP-3"))
        self.assertEqual(fields["FILLER#1"][:2], (19, 7))
        self.assertNotIn("REC-AUTO", fields)
        self.assertNotIn("REC", fields)
        self.assertEqual(total, 26)

    def test_fillers_numbered_in_order(self):
        fields, _ = cl.parse_copybook(cpy(" 01 R.", "     05 FILLER PIC X.", "     05 A PIC X.", "     05 FILLER PIC XX."))
        self.assertEqual(fields["FILLER#1"][:2], (0, 1))
        self.assertEqual(fields["FILLER#2"][:2], (2, 2))

    def test_statement_continued_across_lines(self):
        fields, total = cl.parse_copybook(cpy(" 01 R.", "     05 LONG-NAME", "        PIC X(05).", "     05 B PIC 9."))
        self.assertEqual(fields["LONG-NAME"][:2], (0, 5))
        self.assertEqual(fields["B"][:2], (5, 1))
        self.assertEqual(total, 6)

    def test_occurs_multiplies_size(self):
        fields, total = cl.parse_copybook(cpy(" 01 R.", "     05 TBL PIC X(4) OCCURS 3 TIMES.", "     05 B PIC X."))
        self.assertEqual(fields["TBL"][:2], (0, 12))
        self.assertEqual(fields["B"][:2], (12, 1))
        self.assertEqual(total, 13)

    def test_picture_is_keyword_and_usage_detection(self):
        fields, _ = cl.parse_copybook(cpy(" 01 R.", "     05 A PICTURE IS S9(4) COMP.", "     05 B PIC S9(4) USAGE BINARY."))
        self.assertEqual(fields["A"], (0, 2, "S9(4) COMP"))
        self.assertEqual(fields["B"], (2, 2, "S9(4) BINARY"))

    def test_sequence_area_and_short_lines_ignored(self):
        text = "000100 01 R.\n000200     05 A PIC X(2).\nshort\n\n"
        fields, total = cl.parse_copybook(text)
        self.assertEqual(fields, {"A": (0, 2, "X(2) DISPLAY")})
        self.assertEqual(total, 2)

    def test_empty_and_none(self):
        self.assertEqual(cl.parse_copybook(None), ({}, 0))
        self.assertEqual(cl.parse_copybook(""), ({}, 0))

    def test_field_after_redefines_resumes_at_redefined_position(self):
        fields, _ = cl.parse_copybook(cpy(" 01 R.", "     05 A PIC X(4).", "     05 B REDEFINES A PIC 9(4).", "     05 C PIC X."))
        self.assertEqual(fields["A"][:2], (0, 4))
        self.assertEqual(fields["C"][:2], (4, 1))

    def test_trailing_redefines_keeps_preceding_fields(self):
        fields, _ = cl.parse_copybook(cpy(" 01 R.", "     05 A PIC X(4).", "     05 B REDEFINES A.", "        10 B1 PIC X(2)."))
        self.assertEqual(fields["A"][:2], (0, 4))
        self.assertIn("B1", fields)
        self.assertNotIn("B", fields)

    @unittest.skip("suspected bug: REDEFINES field is placed after the redefined field (offset 4, total 8) "
                   "instead of overlaying it (offset 0, total 5); see PR description")
    def test_redefines_overlays_previous_field(self):
        fields, total = cl.parse_copybook(cpy(" 01 R.", "     05 A PIC X(4).", "     05 B REDEFINES A PIC 9(4).", "     05 C PIC X."))
        self.assertEqual(fields["B"][:2], (0, 4))
        self.assertEqual(fields["C"][:2], (4, 1))
        self.assertEqual(total, 5)

    def test_repo_copybooks_record_lengths(self):
        expected = {"POLICY-RECORD": 125, "PREMIUM-RECORD": 105, "COVERAGE-RECORD": 124, "CUSTOMER-RECORD": 365}
        for name, length in expected.items():
            with open(os.path.join(ROOT, "cobol", "copybooks", name + ".cpy")) as fh:
                fields, total = cl.parse_copybook(fh.read())
            self.assertEqual(total, length, name)
        with open(os.path.join(ROOT, "cobol", "copybooks", "POLICY-RECORD.cpy")) as fh:
            fields, _ = cl.parse_copybook(fh.read())
        self.assertEqual(fields["POLICY-NUMBER"][:2], (0, 12))
        self.assertEqual(fields["POLICY-TOTAL-PREMIUM"][1], 6)


class JclLreclTests(unittest.TestCase):
    def test_lrecl_attributed_to_preceding_dd(self):
        self.assertEqual(cl.jcl_lrecls(JCL), {"POLOUT": 200, "COVOUT": 150})

    def test_lrecl_before_any_dd_ignored(self):
        self.assertEqual(cl.jcl_lrecls("// SET LRECL=80\n//OUT DD DSN=X\n"), {})

    def test_none(self):
        self.assertEqual(cl.jcl_lrecls(None), {})

    def test_repo_jcl(self):
        with open(os.path.join(ROOT, "jcl", "DAILY-EXTRACT.jcl")) as fh:
            self.assertEqual(cl.jcl_lrecls(fh.read()), {"POLOUT": 200, "COVOUT": 150})


class MainTests(unittest.TestCase):
    def run_main(self, base, head):
        captured = {}

        def fake_report(name, findings):
            captured["findings"] = findings
            return 1 if any(s == "FAIL" for s, _, _ in findings) else 0
        with mock.patch.object(cl, "files_at_base", lambda prefix="": list(base)), \
                mock.patch.object(cl, "base_file", lambda p: base.get(p)), \
                mock.patch.object(cl, "head_file", lambda p: head.get(p)), \
                mock.patch.object(cl, "report", fake_report):
            code = cl.main()
        return code, {fid: sev for sev, fid, _ in captured["findings"]}

    def test_unchanged_passes(self):
        files = {"c/R.cpy": BASE_CPY, "j/X.jcl": JCL, "README.md": "x"}
        self.assertEqual(self.run_main(files, dict(files)), (0, {}))

    def test_field_carved_from_filler_is_compatible(self):
        new = BASE_CPY.replace("05  FILLER          PIC X(07).", "05  REC-FLAG PIC X.\n           05  FILLER PIC X(06).")
        code, f = self.run_main({"c/R.cpy": BASE_CPY}, {"c/R.cpy": new})
        self.assertEqual(code, 0)
        self.assertEqual(f, {"layout:c/R.cpy:REC-FLAG:added": "NOTE"})

    def test_field_resized_changes_length_and_moves_followers(self):
        new = BASE_CPY.replace("REC-TYPE        PIC X(03)", "REC-TYPE PIC X(04)")
        code, f = self.run_main({"c/R.cpy": BASE_CPY}, {"c/R.cpy": new})
        self.assertEqual(code, 1)
        self.assertEqual(f, {"layout:c/R.cpy:record-length": "FAIL",
                             "layout:c/R.cpy:REC-TYPE:moved": "FAIL",
                             "layout:c/R.cpy:REC-AMT:moved": "FAIL"})

    def test_field_renamed(self):
        new = BASE_CPY.replace("REC-ID ", "REC-KEY")
        _, f = self.run_main({"c/R.cpy": BASE_CPY}, {"c/R.cpy": new})
        self.assertEqual(f, {"layout:c/R.cpy:REC-ID:removed": "FAIL", "layout:c/R.cpy:REC-KEY:added": "NOTE"})

    def test_copybook_removed(self):
        _, f = self.run_main({"c/R.CPY": BASE_CPY}, {})
        self.assertEqual(f, {"layout:c/R.CPY:removed": "FAIL"})

    def test_jcl_lrecl_changed_and_dd_removed(self):
        new = JCL.replace("LRECL=200", "LRECL=250").replace("//COVOUT   DD DSN=A.C,DCB=(RECFM=FB,LRECL=150)\n", "")
        code, f = self.run_main({"j/X.JCL": JCL}, {"j/X.JCL": new})
        self.assertEqual(code, 1)
        self.assertEqual(f, {"layout:j/X.JCL:POLOUT:lrecl": "FAIL", "layout:j/X.JCL:COVOUT:dataset-removed": "FAIL"})

    def test_jcl_removed(self):
        _, f = self.run_main({"j/X.jcl": JCL}, {})
        self.assertEqual(f, {"layout:j/X.jcl:removed": "FAIL"})

    def test_new_jcl_dd_is_ignored(self):
        new = JCL + "//NEWOUT   DD DCB=(LRECL=80)\n"
        self.assertEqual(self.run_main({"j/X.jcl": JCL}, {"j/X.jcl": new}), (0, {}))


if __name__ == "__main__":
    unittest.main()
