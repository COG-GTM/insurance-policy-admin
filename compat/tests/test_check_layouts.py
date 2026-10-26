import check_layouts as cl


def cpy(body):
    """Render copybook lines in fixed format: cols 1-6 sequence, col 7 indicator, code from col 8."""
    def line(text):
        if not text.strip():
            return ""
        return "000100" + (text if text.startswith("*") else " " + text)
    return "\n".join(line(t) for t in body.strip("\n").splitlines()) + "\n"


BASE_CPY = cpy("""
*  POLICY-RECORD
01  POLICY-RECORD.
    05  POL-NUMBER          PIC X(12).
    05  POL-STATUS          PIC X(02).
        88  POL-ACTIVE      VALUE 'AC'.
    05  POL-PREMIUM         PIC S9(09)V99 COMP-3.
    05  POL-COUNT           PIC S9(04) COMP.
    05  FILLER              PIC X(10).
""")

BASE_JCL = """//EXTRACT  JOB (ACCT),'DAILY'
//STEP010  EXEC PGM=IKJEFT01
//POLOUT   DD DSN=ACME.PAS.EXTRACT.POLICY.DAILY,
//         DISP=(NEW,CATLG,DELETE),
//         DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//COVOUT   DD DSN=ACME.PAS.EXTRACT.COV.DAILY,
//         DCB=(RECFM=FB,LRECL=150,BLKSIZE=0)
"""


# --- pic_size ----------------------------------------------------------------

def test_pic_size_display():
    assert cl.pic_size("X(12)", "DISPLAY") == 12
    assert cl.pic_size("9(08)", "DISPLAY") == 8
    assert cl.pic_size("S9(05)V99", "DISPLAY") == 7  # S and V take no storage
    assert cl.pic_size("XXX", "DISPLAY") == 3


def test_pic_size_packed_decimal():
    assert cl.pic_size("S9(09)V99", "COMP-3") == 6
    assert cl.pic_size("S9(07)V9999", "PACKED-DECIMAL") == 6
    assert cl.pic_size("9(02)", "COMP-3") == 2


def test_pic_size_binary_halfword_fullword_doubleword():
    assert cl.pic_size("S9(04)", "COMP") == 2
    assert cl.pic_size("S9(09)", "BINARY") == 4
    assert cl.pic_size("S9(18)", "COMP-5") == 8
    assert cl.pic_size("9(05)", "COMP-4") == 4


def test_pic_size_lowercase_pic():
    assert cl.pic_size("x(4)", "DISPLAY") == 4


# --- parse_copybook ----------------------------------------------------------

def test_parse_copybook_offsets_sizes_and_total():
    fields, total = cl.parse_copybook(BASE_CPY)
    assert fields == {
        "POL-NUMBER": (0, 12, "X(12) DISPLAY"),
        "POL-STATUS": (12, 2, "X(02) DISPLAY"),
        "POL-PREMIUM": (14, 6, "S9(09)V99 COMP-3"),
        "POL-COUNT": (20, 2, "S9(04) COMP"),
        "FILLER#1": (22, 10, "X(10) DISPLAY"),
    }
    assert total == 32


def test_parse_copybook_occurs_multiplies_elementary_item():
    fields, total = cl.parse_copybook(cpy("""
01  R.
    05  CODES   PIC X(03) OCCURS 4 TIMES.
    05  TAIL    PIC X(01).
"""))
    assert fields["CODES"] == (0, 12, "X(03) DISPLAY")
    assert fields["TAIL"][0] == 12
    assert total == 13


def test_parse_copybook_multiline_statement_and_picture_is():
    fields, _ = cl.parse_copybook(cpy("""
01  R.
    05  LONG-NAME
        PICTURE IS X(20).
    05  NEXT-FIELD  PIC 9(02).
"""))
    assert fields["LONG-NAME"] == (0, 20, "X(20) DISPLAY")
    assert fields["NEXT-FIELD"][0] == 20


def test_parse_copybook_field_after_redefines_resumes_at_original_offset():
    fields, _ = cl.parse_copybook(cpy("""
01  R.
    05  A  PIC X(05).
    05  B  REDEFINES A PIC 9(05).
    05  C  PIC X(03).
"""))
    assert fields["A"] == (0, 5, "X(05) DISPLAY")
    assert fields["C"][0] == 5


def test_parse_copybook_numbers_fillers_and_ignores_comments():
    fields, total = cl.parse_copybook(cpy("""
* comment line with PIC X(99).

01  R.
    05  FILLER  PIC X(02).
    05  FILLER  PIC X(03).
"""))
    assert fields == {"FILLER#1": (0, 2, "X(02) DISPLAY"), "FILLER#2": (2, 3, "X(03) DISPLAY")}
    assert total == 5


def test_parse_copybook_empty():
    assert cl.parse_copybook(None) == ({}, 0)
    assert cl.parse_copybook("") == ({}, 0)


# --- jcl_lrecls --------------------------------------------------------------

def test_jcl_lrecls_maps_continuation_lines_to_dd():
    assert cl.jcl_lrecls(BASE_JCL) == {"POLOUT": 200, "COVOUT": 150}


def test_jcl_lrecls_ignores_lrecl_before_any_dd_and_empty_input():
    assert cl.jcl_lrecls("//* LRECL=80 in a comment\n") == {}
    assert cl.jcl_lrecls(None) == {}


# --- main --------------------------------------------------------------------

def run(repo, capsys, cpy_text=None, jcl_text=None, remove=()):
    repo.write("cobol/copybooks/POLICY-RECORD.cpy", BASE_CPY)
    repo.write("jcl/DAILY-EXTRACT.jcl", BASE_JCL)
    repo.write("README.md", "not a layout")
    repo.commit("base")
    repo.branch()
    if cpy_text is not None:
        repo.write("cobol/copybooks/POLICY-RECORD.cpy", cpy_text)
    if jcl_text is not None:
        repo.write("jcl/DAILY-EXTRACT.jcl", jcl_text)
    for p in remove:
        repo.remove(p)
    repo.commit()
    rc = cl.main()
    return rc, capsys.readouterr().out


def test_main_unchanged_passes(repo, capsys):
    rc, out = run(repo, capsys)
    assert rc == 0
    assert "== Record & file layout compatibility: PASS" in out


def test_main_field_carved_from_filler_is_note(repo, capsys):
    new = BASE_CPY.replace("05  FILLER              PIC X(10).", "05  POL-API-FLAG  PIC X(01).\n000100     05  FILLER  PIC X(09).")
    rc, out = run(repo, capsys, cpy_text=new)
    assert rc == 0
    assert "NOTE      layout:cobol/copybooks/POLICY-RECORD.cpy:POL-API-FLAG:added: new field at offset 22" in out


def test_main_record_length_change_fails(repo, capsys):
    new = BASE_CPY.replace("FILLER              PIC X(10)", "FILLER              PIC X(12)")
    rc, out = run(repo, capsys, cpy_text=new)
    assert rc == 1
    assert "layout:cobol/copybooks/POLICY-RECORD.cpy:record-length: record length 32 -> 34 bytes" in out


def test_main_moved_field_fails_even_when_length_kept(repo, capsys):
    new = BASE_CPY.replace("POL-STATUS          PIC X(02)", "POL-STATUS          PIC X(03)").replace(
        "FILLER              PIC X(10)", "FILLER              PIC X(09)")
    rc, out = run(repo, capsys, cpy_text=new)
    assert rc == 1
    assert "record-length" not in out
    assert "POL-STATUS:moved: offset/size 12/2 -> 12/3" in out
    assert "POL-PREMIUM:moved: offset/size 14/6 -> 15/6" in out


def test_main_usage_change_that_resizes_field_fails(repo, capsys):
    new = BASE_CPY.replace("S9(09)V99 COMP-3", "S9(09)V99")
    rc, out = run(repo, capsys, cpy_text=new)
    assert rc == 1
    assert "POL-PREMIUM:moved: offset/size 14/6 -> 14/11 (S9(09)V99 COMP-3 -> S9(09)V99 DISPLAY)" in out


def test_main_renamed_field_fails(repo, capsys):
    rc, out = run(repo, capsys, cpy_text=BASE_CPY.replace("POL-COUNT", "POL-CNT  "))
    assert rc == 1
    assert "POL-COUNT:removed: field removed/renamed (was offset 20, S9(04) COMP)" in out


def test_main_removed_copybook_fails(repo, capsys):
    rc, out = run(repo, capsys, remove=["cobol/copybooks/POLICY-RECORD.cpy"])
    assert rc == 1
    assert "layout:cobol/copybooks/POLICY-RECORD.cpy:removed: copybook removed" in out


def test_main_lrecl_change_fails(repo, capsys):
    rc, out = run(repo, capsys, jcl_text=BASE_JCL.replace("LRECL=200", "LRECL=250"))
    assert rc == 1
    assert "layout:jcl/DAILY-EXTRACT.jcl:POLOUT:lrecl: LRECL 200 -> 250" in out


def test_main_output_dd_removed_fails(repo, capsys):
    jcl = BASE_JCL[:BASE_JCL.index("//COVOUT")]
    rc, out = run(repo, capsys, jcl_text=jcl)
    assert rc == 1
    assert "layout:jcl/DAILY-EXTRACT.jcl:COVOUT:dataset-removed: output DD COVOUT (LRECL=150) removed" in out


def test_main_removed_jcl_fails(repo, capsys):
    rc, out = run(repo, capsys, remove=["jcl/DAILY-EXTRACT.jcl"])
    assert rc == 1
    assert "layout:jcl/DAILY-EXTRACT.jcl:removed" in out


def test_main_new_dd_is_fine(repo, capsys):
    jcl = BASE_JCL + "//NEWOUT   DD DSN=X,\n//         DCB=(LRECL=80)\n"
    rc, _ = run(repo, capsys, jcl_text=jcl)
    assert rc == 0
