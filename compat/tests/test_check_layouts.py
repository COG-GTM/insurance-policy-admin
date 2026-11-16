import os
import unittest

from helpers import COMPAT_DIR, allowed_changes, fake_repo, run_quiet

import check_layouts

CPY = "cobol/copybooks/TEST-RECORD.cpy"
JCL = "jcl/TEST-EXTRACT.jcl"


def copybook(*lines):
    """Lay statements out in COBOL area A/B (columns 8-72)."""
    return "\n".join("       " + ln for ln in lines) + "\n"


BASE_CPY = copybook(
    "01  TEST-RECORD.",
    "    05  T-POLICY-NUMBER   PIC X(12).",
    "    05  T-STATUS          PIC X(02).",
    "        88  T-ACTIVE      VALUE 'AC'.",
    "    05  T-PREMIUM         PIC S9(09)V99 COMP-3.",
    "    05  FILLER            PIC X(10).",
)

BASE_JCL = """//TESTJOB  JOB (ACCT),'TEST'
//STEP01   EXEC PGM=IKJEFT01
//SYSPRINT DD SYSOUT=*
//POLOUT   DD DSN=ACME.PAS.EXTRACT,
//            DISP=(NEW,CATLG),
//         DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//COVOUT   DD DSN=ACME.PAS.COV,DCB=(RECFM=FB,LRECL=150)
"""


class PicSizeTests(unittest.TestCase):
    def test_display_sizes(self):
        self.assertEqual(check_layouts.pic_size("X(12)", "DISPLAY"), 12)
        self.assertEqual(check_layouts.pic_size("9(08)", "DISPLAY"), 8)
        self.assertEqual(check_layouts.pic_size("s9(7)v99", "DISPLAY"), 9)  # S and V take no storage
        self.assertEqual(check_layouts.pic_size("ZZ9.99", "DISPLAY"), 6)

    def test_packed_decimal_sizes(self):
        self.assertEqual(check_layouts.pic_size("S9(11)V99", "COMP-3"), 7)
        self.assertEqual(check_layouts.pic_size("S9(07)V99", "PACKED-DECIMAL"), 5)
        self.assertEqual(check_layouts.pic_size("9", "COMP-3"), 1)

    def test_binary_sizes(self):
        self.assertEqual(check_layouts.pic_size("S9(4)", "COMP"), 2)
        self.assertEqual(check_layouts.pic_size("9(5)", "BINARY"), 4)
        self.assertEqual(check_layouts.pic_size("S9(9)", "COMP-4"), 4)
        self.assertEqual(check_layouts.pic_size("S9(18)", "COMP-5"), 8)


class ParseCopybookTests(unittest.TestCase):
    def test_offsets_sizes_and_total(self):
        fields, total = check_layouts.parse_copybook(BASE_CPY)
        self.assertEqual(
            fields,
            {
                "T-POLICY-NUMBER": (0, 12, "X(12) DISPLAY"),
                "T-STATUS": (12, 2, "X(02) DISPLAY"),
                "T-PREMIUM": (14, 6, "S9(09)V99 COMP-3"),
                "FILLER#1": (20, 10, "X(10) DISPLAY"),
            },
        )
        self.assertEqual(total, 30)

    def test_skips_comments_blank_and_sequence_area(self):
        text = (
            "000100* COMMENT LINE PIC X(99).\n"
            "\n"
            "000200 01  R.\n" + "000300     05  A  PIC X(03).".ljust(72) + "SEQNUM01\n"
            "000400     05  B  PIC\n"
            "000500             9(04) COMP.\n"
        )
        fields, total = check_layouts.parse_copybook(text)
        self.assertEqual(fields, {"A": (0, 3, "X(03) DISPLAY"), "B": (3, 2, "9(04) COMP")})
        self.assertEqual(total, 5)

    def test_occurs_on_elementary_item_and_picture_is_keyword(self):
        fields, total = check_layouts.parse_copybook(
            copybook(
                "01  R.",
                "    05  CODES  PICTURE IS X(04) OCCURS 3 TIMES.",
                "    05  FILLER PIC X(2).",
                "    05  FILLER PIC X(1).",
            )
        )
        self.assertEqual(fields["CODES"], (0, 12, "X(04) DISPLAY"))
        self.assertEqual(sorted(fields), ["CODES", "FILLER#1", "FILLER#2"])
        self.assertEqual(total, 15)

    def test_empty_input(self):
        self.assertEqual(check_layouts.parse_copybook(None), ({}, 0))
        self.assertEqual(check_layouts.parse_copybook(""), ({}, 0))

    def test_real_repo_copybook(self):
        path = os.path.join(os.path.dirname(COMPAT_DIR), "cobol", "copybooks", "COVERAGE-RECORD.cpy")
        with open(path) as fh:
            fields, total = check_layouts.parse_copybook(fh.read())
        self.assertEqual(fields["COV-POLICY-NUMBER"], (0, 12, "X(12) DISPLAY"))
        self.assertEqual(fields["COV-LIMIT"][1], 7)
        self.assertNotIn("COV-STAT-ACTIVE", fields)  # 88-levels are not storage
        self.assertEqual(total, 124)


class JclLreclTests(unittest.TestCase):
    def test_tracks_lrecl_per_dd_including_continuations(self):
        self.assertEqual(check_layouts.jcl_lrecls(BASE_JCL), {"POLOUT": 200, "COVOUT": 150})

    def test_ignores_lrecl_before_any_dd(self):
        self.assertEqual(check_layouts.jcl_lrecls("// SET LRECL=80\n//OUT DD SYSOUT=*\n"), {})
        self.assertEqual(check_layouts.jcl_lrecls(None), {})


class MainTests(unittest.TestCase):
    def run_main(self, base, head, allowed=()):
        with allowed_changes(allowed), fake_repo(check_layouts, base, head):
            return run_quiet(check_layouts.main)

    def test_unchanged_files_pass(self):
        code, out = self.run_main({CPY: BASE_CPY, JCL: BASE_JCL, "README.md": "x"}, {CPY: BASE_CPY, JCL: BASE_JCL})
        self.assertEqual(code, 0)
        self.assertIn("== Record & file layout compatibility: PASS", out)

    def test_field_carved_from_filler_is_compatible(self):
        head = BASE_CPY.replace(
            "05  FILLER            PIC X(10).", "05  T-NEW-FLAG        PIC X(01).\n           05  FILLER  PIC X(09)."
        )
        code, out = self.run_main({CPY: BASE_CPY}, {CPY: head})
        self.assertEqual(code, 0, out)
        self.assertIn(f"NOTE      layout:{CPY}:T-NEW-FLAG:added: new field at offset 20", out)

    def test_widened_field_moves_successors_and_changes_length(self):
        head = BASE_CPY.replace("PIC X(02).", "PIC X(03).")
        code, out = self.run_main({CPY: BASE_CPY}, {CPY: head})
        self.assertEqual(code, 1)
        self.assertIn(f"layout:{CPY}:record-length: record length 30 -> 31 bytes", out)
        self.assertIn(f"layout:{CPY}:T-STATUS:moved: offset/size 12/2 -> 12/3", out)
        self.assertIn(f"layout:{CPY}:T-PREMIUM:moved: offset/size 14/6 -> 15/6", out)
        self.assertNotIn("FILLER#1", out)

    def test_renamed_field_and_removed_copybook(self):
        head = BASE_CPY.replace("T-STATUS ", "T-STAT   ")
        code, out = self.run_main({CPY: BASE_CPY}, {CPY: head})
        self.assertEqual(code, 1)
        self.assertIn(f"layout:{CPY}:T-STATUS:removed: field removed/renamed (was offset 12, X(02) DISPLAY)", out)
        self.assertIn(f"layout:{CPY}:T-STAT:added", out)
        code, out = self.run_main({CPY: BASE_CPY}, {})
        self.assertEqual(code, 1)
        self.assertIn(f"layout:{CPY}:removed: copybook removed", out)

    def test_jcl_lrecl_change_and_removed_dataset(self):
        head = BASE_JCL.replace("LRECL=200", "LRECL=250").replace("//COVOUT ", "//COVNEW ")
        code, out = self.run_main({JCL: BASE_JCL}, {JCL: head})
        self.assertEqual(code, 1)
        self.assertIn(f"layout:{JCL}:POLOUT:lrecl: LRECL 200 -> 250", out)
        self.assertIn(f"layout:{JCL}:COVOUT:dataset-removed: output DD COVOUT (LRECL=150) removed", out)

    def test_removed_jcl_job(self):
        code, out = self.run_main({JCL: BASE_JCL}, {})
        self.assertEqual(code, 1)
        self.assertIn(f"layout:{JCL}:removed: JCL job removed; replacement must be approved", out)

    def test_allow_listed_lrecl_change(self):
        head = BASE_JCL.replace("LRECL=150", "LRECL=180")
        fid = f"layout:{JCL}:COVOUT:lrecl"
        code, out = self.run_main({JCL: BASE_JCL}, {JCL: head}, [{"id": fid, "ticket": "AB-530", "reason": "feed v2"}])
        self.assertEqual(code, 0)
        self.assertIn(f"APPROVED  {fid}", out)


if __name__ == "__main__":
    unittest.main()
