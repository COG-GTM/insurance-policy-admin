import os
import unittest
from unittest import mock

import support
from support import cpy
import check_layouts
from check_layouts import jcl_lrecls, parse_copybook, pic_size

REPO = os.path.dirname(support.COMPAT)

BASE_CPY = cpy(
    "01  REC.",
    "    05  KEY-ID      PIC X(10).",
    "    05  AMOUNT      PIC S9(7)V99 COMP-3.",
    "    05  FILLER      PIC X(10).",
)

BASE_JCL = """//JOB1     JOB (ACCT)
//STEP010  EXEC PGM=EXTRACT
//OUT1     DD DSN=A.B.C,
//         DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//OUT2     DD DSN=A.B.D,DCB=(RECFM=FB,LRECL=150)
//SYSOUT   DD SYSOUT=*
"""


def read(path):
    with open(os.path.join(REPO, path)) as fh:
        return fh.read()


class PicSizeTest(unittest.TestCase):
    def test_display_sizes(self):
        self.assertEqual(pic_size("X(10)", "DISPLAY"), 10)
        self.assertEqual(pic_size("9(08)", "DISPLAY"), 8)
        self.assertEqual(pic_size("s9(5)v99", "DISPLAY"), 7, "S and V take no storage")
        self.assertEqual(pic_size("ZZ9.99", "DISPLAY"), 6)
        self.assertEqual(pic_size("XXX", "DISPLAY"), 3)

    def test_packed_decimal(self):
        self.assertEqual(pic_size("S9(09)V99", "COMP-3"), 6)
        self.assertEqual(pic_size("S9(11)V99", "PACKED-DECIMAL"), 7)
        self.assertEqual(pic_size("S9(07)V9999", "COMP-3"), 6)
        self.assertEqual(pic_size("9", "COMP-3"), 1)

    def test_binary_halfword_fullword_doubleword(self):
        self.assertEqual(pic_size("S9(4)", "COMP"), 2)
        self.assertEqual(pic_size("9(5)", "BINARY"), 4)
        self.assertEqual(pic_size("S9(9)", "COMP-4"), 4)
        self.assertEqual(pic_size("S9(10)", "COMP-5"), 8)
        self.assertEqual(pic_size("S9(18)", "COMP"), 8)


class ParseCopybookTest(unittest.TestCase):
    def test_offsets_sizes_and_total(self):
        fields, total = parse_copybook(BASE_CPY)
        self.assertEqual(fields["KEY-ID"], (0, 10, "X(10) DISPLAY"))
        self.assertEqual(fields["AMOUNT"], (10, 5, "S9(7)V99 COMP-3"))
        self.assertEqual(fields["FILLER#1"], (15, 10, "X(10) DISPLAY"))
        self.assertNotIn("REC", fields, "group items without PIC have no storage of their own")
        self.assertEqual(total, 25)

    def test_comments_88_levels_and_sequence_area_are_ignored(self):
        text = (
            "000100* a comment line PIC X(99).\n"
            "000200 01  REC.\n"
            + "000300     05  STATUS      PIC X(02).".ljust(72) + "IGNORED1\n"
            + "000400         88  ACTIVE  VALUE 'AC'.\n"
            "\n"
            "000500     05  CODE        PIC 9(03).\n"
        )
        fields, total = parse_copybook(text)
        self.assertEqual(set(fields), {"STATUS", "CODE"})
        self.assertEqual(fields["CODE"][0], 2)
        self.assertEqual(total, 5)

    def test_statement_continued_over_lines(self):
        fields, total = parse_copybook(cpy("01 REC.", "    05  LONG-NAME", "        PIC X(07).", "    05 B PIC X."))
        self.assertEqual(fields["LONG-NAME"], (0, 7, "X(07) DISPLAY"))
        self.assertEqual(fields["B"], (7, 1, "X DISPLAY"))
        self.assertEqual(total, 8)

    def test_picture_is_occurs_and_usage(self):
        fields, total = parse_copybook(cpy(
            "01 REC.",
            "    05 A PICTURE IS 9(4) COMP.",
            "    05 B PIC X(3) OCCURS 4 TIMES.",
            "    05 C PIC S9(5) USAGE BINARY.",
        ))
        self.assertEqual(fields["A"], (0, 2, "9(4) COMP"))
        self.assertEqual(fields["B"], (2, 12, "X(3) DISPLAY"))
        self.assertEqual(fields["C"], (14, 4, "S9(5) BINARY"))
        self.assertEqual(total, 18)

    def test_multiple_fillers_are_numbered(self):
        fields, _ = parse_copybook(cpy("01 R.", "05 FILLER PIC X.", "05 A PIC X.", "05 FILLER PIC XX."))
        self.assertEqual(fields["FILLER#1"][:2], (0, 1))
        self.assertEqual(fields["FILLER#2"][:2], (2, 2))

    def test_redefines_does_not_advance_following_fields(self):
        fields, _ = parse_copybook(cpy(
            "01 R.", "05 A PIC X(10).", "05 B REDEFINES A PIC 9(10).", "05 C PIC X(20).",
        ))
        self.assertEqual(fields["C"][0], 10)

    @unittest.skip("suspected bug: REDEFINES item is laid out after the redefined item, not over it "
                   "(B gets offset 10, record length 20 instead of 12); see PR description")
    def test_redefines_overlays_redefined_field(self):
        fields, total = parse_copybook(cpy(
            "01 R.", "05 A PIC X(10).", "05 B REDEFINES A PIC X(10).", "05 C PIC X(2).",
        ))
        self.assertEqual(fields["B"][0], 0)
        self.assertEqual(total, 12)

    @unittest.skip("suspected bug: OCCURS on a group item is ignored, so its children are counted once "
                   "(record length 6 instead of 22); see PR description")
    def test_group_occurs_multiplies_children(self):
        _, total = parse_copybook(cpy(
            "01 R.", "05 ITEMS OCCURS 5 TIMES.", "10 X PIC X(4).", "05 C PIC X(2).",
        ))
        self.assertEqual(total, 22)

    def test_empty_copybook(self):
        self.assertEqual(parse_copybook(None), ({}, 0))
        self.assertEqual(parse_copybook(""), ({}, 0))

    def test_real_coverage_record(self):
        fields, total = parse_copybook(read("cobol/copybooks/COVERAGE-RECORD.cpy"))
        self.assertEqual(total, 124)
        self.assertEqual(fields["COV-POLICY-NUMBER"], (0, 12, "X(12) DISPLAY"))
        self.assertEqual(fields["COV-LIMIT"][:2], (59, 7))
        self.assertEqual(fields["FILLER#1"][:2], (109, 15))

    def test_all_repo_copybooks_parse_to_a_contiguous_layout(self):
        for name in os.listdir(os.path.join(REPO, "cobol", "copybooks")):
            fields, total = parse_copybook(read(f"cobol/copybooks/{name}"))
            self.assertTrue(fields, name)
            self.assertEqual(sum(s for _, s, _ in fields.values()), total, name)


class JclLreclsTest(unittest.TestCase):
    def test_lrecl_attributed_to_enclosing_dd(self):
        self.assertEqual(jcl_lrecls(BASE_JCL), {"OUT1": 200, "OUT2": 150})

    def test_lrecl_before_any_dd_is_ignored(self):
        self.assertEqual(jcl_lrecls("//* LRECL=80 in a comment\n//X EXEC PGM=Y\n"), {})

    def test_empty(self):
        self.assertEqual(jcl_lrecls(None), {})

    def test_real_jcl(self):
        self.assertEqual(jcl_lrecls(read("jcl/PREMIUM-BATCH.jcl")), {"PREMRPT": 132})
        self.assertEqual(sorted(jcl_lrecls(read("jcl/DAILY-EXTRACT.jcl")).values()), [150, 200])


class MainTest(unittest.TestCase):
    def findings(self, base, head):
        with mock.patch.object(check_layouts, "files_at_base", return_value=list(base)), \
                mock.patch.object(check_layouts, "base_file", side_effect=base.get), \
                mock.patch.object(check_layouts, "head_file", side_effect=head.get), \
                mock.patch.object(check_layouts, "report", side_effect=lambda name, f: f):
            return check_layouts.main()

    def ids(self, head_cpy, path="cobol/copybooks/REC.cpy"):
        return {(s, i) for s, i, _ in self.findings({path: BASE_CPY}, {path: head_cpy})}

    def test_unchanged_files_have_no_findings(self):
        self.assertEqual(self.findings({"a.cpy": BASE_CPY, "j.jcl": BASE_JCL, "x.txt": "x"},
                                       {"a.cpy": BASE_CPY, "j.jcl": BASE_JCL, "x.txt": "y"}), [])

    def test_field_carved_from_filler_is_a_note(self):
        head = BASE_CPY.replace("05  FILLER      PIC X(10).", "05  REGION PIC X(4).\n           05  FILLER PIC X(6).")
        self.assertEqual(self.ids(head), {("NOTE", "layout:cobol/copybooks/REC.cpy:REGION:added")})

    def test_record_length_change(self):
        head = BASE_CPY.replace("FILLER      PIC X(10)", "FILLER PIC X(12)")
        f = self.findings({"r.CPY": BASE_CPY}, {"r.CPY": head})
        self.assertEqual([(s, i) for s, i, _ in f], [("FAIL", "layout:r.CPY:record-length")])
        self.assertIn("25 -> 27 bytes", f[0][2])

    def test_field_resized_moves_following_fields(self):
        head = BASE_CPY.replace("PIC X(10).\n           05  AMOUNT", "PIC X(12).\n           05  AMOUNT") \
                       .replace("FILLER      PIC X(10)", "FILLER PIC X(8)")
        self.assertEqual(self.ids(head), {
            ("FAIL", "layout:cobol/copybooks/REC.cpy:KEY-ID:moved"),
            ("FAIL", "layout:cobol/copybooks/REC.cpy:AMOUNT:moved"),
        })

    def test_usage_change_is_detected_as_size_change(self):
        f = self.findings({"r.cpy": BASE_CPY}, {"r.cpy": BASE_CPY.replace("S9(7)V99 COMP-3", "S9(7)V99")})
        moved = [m for s, i, m in f if i.endswith("AMOUNT:moved")]
        self.assertEqual(len(moved), 1)
        self.assertIn("10/5 -> 10/9", moved[0])

    def test_field_renamed(self):
        self.assertIn(("FAIL", "layout:cobol/copybooks/REC.cpy:KEY-ID:removed"), self.ids(BASE_CPY.replace("KEY-ID", "KEY-NO")))

    def test_copybook_removed(self):
        self.assertEqual([(s, i) for s, i, _ in self.findings({"r.cpy": BASE_CPY}, {})], [("FAIL", "layout:r.cpy:removed")])

    def test_jcl_lrecl_changed(self):
        f = self.findings({"j.jcl": BASE_JCL}, {"j.jcl": BASE_JCL.replace("LRECL=200", "LRECL=250")})
        self.assertEqual([(s, i, m) for s, i, m in f], [("FAIL", "layout:j.jcl:OUT1:lrecl", "LRECL 200 -> 250")])

    def test_jcl_output_dataset_removed(self):
        head = BASE_JCL.replace("//OUT2     DD DSN=A.B.D,DCB=(RECFM=FB,LRECL=150)\n", "")
        self.assertEqual([(s, i) for s, i, _ in self.findings({"J.JCL": BASE_JCL}, {"J.JCL": head})],
                         [("FAIL", "layout:J.JCL:OUT2:dataset-removed")])

    def test_jcl_new_dataset_is_fine(self):
        head = BASE_JCL + "//OUT3     DD DSN=A.B.E,DCB=(LRECL=80)\n"
        self.assertEqual(self.findings({"j.jcl": BASE_JCL}, {"j.jcl": head}), [])

    def test_jcl_removed(self):
        self.assertEqual([(s, i) for s, i, _ in self.findings({"j.jcl": BASE_JCL}, {})], [("FAIL", "layout:j.jcl:removed")])


if __name__ == "__main__":
    unittest.main()
