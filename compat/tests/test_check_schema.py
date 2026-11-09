import unittest
from unittest import mock

import support  # noqa: F401
import check_schema
from check_schema import narrowed, parse, split_top, strip_comments

BASE = """
-- core table
CREATE TABLE S.T (
    ID      INTEGER       NOT NULL,
    NAME    VARCHAR(20),
    AMT     DECIMAL(9,2)  DEFAULT 0,
    CODE    CHAR(4),
    CONSTRAINT PK_T PRIMARY KEY (ID)
);
CREATE SEQUENCE S.SEQ START WITH 1 INCREMENT BY 1;
"""


def col(type_, length=None, scale=None):
    return {"type": type_, "len": length, "scale": scale}


class ParseTest(unittest.TestCase):
    def test_strip_comments_removes_line_comments(self):
        self.assertEqual(strip_comments("A -- x\nB--y\n"), "A \nB\n")

    def test_split_top_ignores_commas_inside_parentheses(self):
        self.assertEqual(split_top(" A DECIMAL(9,2), B CHAR(1) ,, "), ["A DECIMAL(9,2)", "B CHAR(1)"])

    def test_parse_columns_and_constraints(self):
        tables, seqs = parse(BASE)
        t = tables["S.T"]
        self.assertEqual(set(t["cols"]), {"ID", "NAME", "AMT", "CODE"})
        self.assertEqual(t["cols"]["AMT"], {"type": "DECIMAL", "len": 9, "scale": 2, "notnull": False,
                                            "default": True, "raw": "DECIMAL(9,2) DEFAULT 0"})
        self.assertTrue(t["cols"]["ID"]["notnull"])
        self.assertIsNone(t["cols"]["ID"]["len"])
        self.assertEqual(t["constraints"], {"PRIMARY KEY:PK_T"})
        self.assertEqual(seqs, {"S.SEQ": "START WITH 1 INCREMENT BY 1"})

    def test_parse_is_case_insensitive_and_handles_if_not_exists(self):
        tables, _ = parse("create table if not exists acmeins.x (a char(2) not null default 'A');")
        self.assertEqual(list(tables), ["ACMEINS.X"])
        self.assertTrue(tables["ACMEINS.X"]["cols"]["A"]["default"])

    def test_parse_ignores_commented_out_tables_and_empty_input(self):
        self.assertEqual(parse("-- CREATE TABLE GONE (A INT);"), ({}, {}))
        self.assertEqual(parse(None), ({}, {}))

    def test_parse_skips_single_token_parts(self):
        tables, _ = parse("CREATE TABLE T (A INTEGER, ORPHAN);")
        self.assertEqual(list(tables["T"]["cols"]), ["A"])

    def test_parse_real_ddl(self):
        with open(support.os.path.join(support.COMPAT, "..", "sql", "ddl", "create-tables.sql")) as fh:
            tables, _ = parse(fh.read())
        self.assertIn("ACMEINS.POLICIES", tables)
        pol = tables["ACMEINS.POLICIES"]["cols"]
        self.assertEqual((pol["POLICY_NUMBER"]["type"], pol["POLICY_NUMBER"]["len"]), ("CHAR", 12))
        self.assertTrue(pol["POLICY_STATUS"]["default"])


class NarrowedTest(unittest.TestCase):
    def test_char_length(self):
        self.assertTrue(narrowed(col("VARCHAR", 20), col("VARCHAR", 10)))
        self.assertFalse(narrowed(col("VARCHAR", 20), col("VARCHAR", 30)))
        self.assertFalse(narrowed(col("DATE"), col("DATE")))

    def test_char_varchar_switch_compares_length(self):
        self.assertFalse(narrowed(col("CHAR", 4), col("VARCHAR", 4)))
        self.assertTrue(narrowed(col("CHAR", 4), col("VARCHAR", 3)))

    def test_decimal_precision_and_scale(self):
        self.assertFalse(narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 11, 2)))
        self.assertTrue(narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 9, 1)), "scale reduced")
        self.assertTrue(narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 9, 3)), "integer digits reduced")
        self.assertFalse(narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 10, 3)))

    def test_integer_ranks(self):
        self.assertTrue(narrowed(col("INTEGER"), col("SMALLINT")))
        self.assertFalse(narrowed(col("INTEGER"), col("BIGINT")))
        self.assertFalse(narrowed(col("INT"), col("INTEGER")))

    def test_other_type_changes_are_incompatible(self):
        self.assertTrue(narrowed(col("DECIMAL", 9, 2), col("DATE")))
        self.assertTrue(narrowed(col("INTEGER"), col("DECIMAL", 11, 0)))


class MainTest(unittest.TestCase):
    """Drives main() with in-memory base/head files; report() returns the findings."""

    def findings(self, base, head, extra_base_files=()):
        base = dict(base)
        files = list(base) + list(extra_base_files)
        with mock.patch.object(check_schema, "files_at_base", return_value=files), \
                mock.patch.object(check_schema, "base_file", side_effect=base.get), \
                mock.patch.object(check_schema, "head_file", side_effect=head.get), \
                mock.patch.object(check_schema, "report", side_effect=lambda name, f: f):
            return check_schema.main()

    def ids(self, head_sql, path="sql/ddl/t.sql"):
        return {(sev, fid) for sev, fid, _ in self.findings({path: BASE}, {path: head_sql})}

    def test_unchanged_schema_has_no_findings(self):
        self.assertEqual(self.ids(BASE), set())

    def test_non_schema_files_are_ignored(self):
        self.assertEqual(self.findings({"docs/x.sql": BASE, "sql/readme.md": BASE}, {}), [])

    def test_resources_schema_sql_is_checked(self):
        path = "java-facade/src/main/resources/schema.sql"
        self.assertEqual(self.ids("", path), {("FAIL", "schema:S.T:table-dropped"), ("FAIL", "schema:S.SEQ:sequence-dropped")})

    def test_table_dropped(self):
        self.assertIn(("FAIL", "schema:S.T:table-dropped"), self.ids("CREATE SEQUENCE S.SEQ START WITH 1 INCREMENT BY 1;"))

    def test_column_dropped(self):
        self.assertIn(("FAIL", "schema:S.T.CODE:column-dropped"), self.ids(BASE.replace("CODE    CHAR(4),", "")))

    def test_type_narrowed(self):
        self.assertEqual(self.ids(BASE.replace("VARCHAR(20)", "VARCHAR(10)")), {("FAIL", "schema:S.T.NAME:type-narrowed")})
        self.assertEqual(self.ids(BASE.replace("ID      INTEGER", "ID SMALLINT")), {("FAIL", "schema:S.T.ID:type-narrowed")})

    def test_type_changed(self):
        f = self.findings({"sql/t.sql": BASE}, {"sql/t.sql": BASE.replace("DECIMAL(9,2)", "DATE")})
        self.assertEqual([(s, i) for s, i, _ in f], [("FAIL", "schema:S.T.AMT:type-changed")])
        self.assertIn("DECIMAL(9,2) DEFAULT 0 -> DATE DEFAULT 0", f[0][2])

    def test_widening_is_compatible(self):
        self.assertEqual(self.ids(BASE.replace("VARCHAR(20)", "VARCHAR(40)").replace("DECIMAL(9,2)", "DECIMAL(11,2)")), set())

    def test_made_not_null_without_default(self):
        self.assertEqual(self.ids(BASE.replace("CODE    CHAR(4),", "CODE CHAR(4) NOT NULL,")),
                         {("FAIL", "schema:S.T.CODE:made-not-null")})

    def test_made_not_null_with_default_is_compatible(self):
        self.assertEqual(self.ids(BASE.replace("CODE    CHAR(4),", "CODE CHAR(4) NOT NULL DEFAULT 'X',")), set())

    def test_new_required_column(self):
        head = BASE.replace("CODE    CHAR(4),", "CODE CHAR(4), REGION CHAR(2) NOT NULL,")
        self.assertEqual(self.ids(head), {("FAIL", "schema:S.T.REGION:new-required-column")})

    def test_new_nullable_or_defaulted_column_is_a_note(self):
        head = BASE.replace("CODE    CHAR(4),", "CODE CHAR(4), A CHAR(2), B CHAR(1) NOT NULL DEFAULT 'N',")
        self.assertEqual(self.ids(head), {("NOTE", "schema:S.T.A:column-added"), ("NOTE", "schema:S.T.B:column-added")})

    def test_constraint_removed(self):
        head = BASE.replace(",\n    CONSTRAINT PK_T PRIMARY KEY (ID)", "")
        self.assertEqual(self.ids(head), {("FAIL", "schema:S.T:PRIMARY KEY:PK_T:constraint-removed")})

    def test_sequence_dropped_or_changed(self):
        self.assertEqual(self.ids(BASE.replace("CREATE SEQUENCE S.SEQ START WITH 1 INCREMENT BY 1;", "")),
                         {("FAIL", "schema:S.SEQ:sequence-dropped")})
        self.assertEqual(self.ids(BASE.replace("INCREMENT BY 1", "INCREMENT BY 10")),
                         {("FAIL", "schema:S.SEQ:sequence-changed")})

    def test_schema_file_removed(self):
        f = self.findings({"sql/t.sql": BASE}, {})
        self.assertEqual([(s, i) for s, i, _ in f], [("FAIL", "schema:sql/t.sql:file-removed")])
        self.assertIn("1 tables", f[0][2])

    def test_removing_schema_file_without_tables_is_fine(self):
        self.assertEqual(self.findings({"sql/empty.sql": "-- nothing\n"}, {}), [])

    def test_applied_migration_modified(self):
        mig = "db/migration/V1__init.sql"
        f = self.findings({mig: "CREATE TABLE A (X INT);"}, {mig: "CREATE TABLE A (X BIGINT);"})
        self.assertEqual([(s, i) for s, i, _ in f], [("FAIL", f"schema:{mig}:applied-migration-modified")])

    def test_unchanged_migration_is_fine(self):
        mig = "svc/src/main/resources/db/migration/V2__more.sql"
        self.assertEqual(self.findings({mig: "x"}, {mig: "x"}), [])

    def test_main_reports_under_check_name(self):
        with mock.patch.object(check_schema, "files_at_base", return_value=[]), \
                mock.patch.object(check_schema, "report", return_value=0) as rep:
            self.assertEqual(check_schema.main(), 0)
        rep.assert_called_once_with("DB schema compatibility", [])


if __name__ == "__main__":
    unittest.main()
