import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import check_schema as cs  # noqa: E402
from common import ROOT  # noqa: E402

BASE_SQL = """
-- policies table
CREATE TABLE ACMEINS.POLICIES (
    POLICY_NUMBER   CHAR(12)      NOT NULL,
    POLICY_TYPE     CHAR(3)       NOT NULL,  -- AUT/HOM
    TOTAL_PREMIUM   DECIMAL(11,2),
    RENEWAL_COUNT   INTEGER       DEFAULT 0,
    AGENT_CODE      VARCHAR(6),
    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER),
    CONSTRAINT CK_TYPE CHECK (POLICY_TYPE IN ('AUT', 'HOM'))
);
CREATE TABLE ACMEINS.NOTES (
    NOTE_ID INTEGER NOT NULL
);
CREATE SEQUENCE ACMEINS.POLICY_SEQ START WITH 1 INCREMENT BY 1;
"""


def col(type_, length=None, scale=None):
    return {"type": type_, "len": length, "scale": scale}


class ParseTests(unittest.TestCase):
    def test_strip_comments(self):
        self.assertEqual(cs.strip_comments("A -- note\nB"), "A \nB")

    def test_split_top_ignores_nested_commas(self):
        self.assertEqual(cs.split_top("A DECIMAL(11,2), B CHAR(1) , "), ["A DECIMAL(11,2)", "B CHAR(1)"])

    def test_parse_columns_and_attributes(self):
        tables, _ = cs.parse(BASE_SQL)
        self.assertEqual(set(tables), {"ACMEINS.POLICIES", "ACMEINS.NOTES"})
        cols = tables["ACMEINS.POLICIES"]["cols"]
        self.assertEqual(list(cols), ["POLICY_NUMBER", "POLICY_TYPE", "TOTAL_PREMIUM", "RENEWAL_COUNT", "AGENT_CODE"])
        self.assertEqual(cols["TOTAL_PREMIUM"]["type"], "DECIMAL")
        self.assertEqual((cols["TOTAL_PREMIUM"]["len"], cols["TOTAL_PREMIUM"]["scale"]), (11, 2))
        self.assertTrue(cols["POLICY_NUMBER"]["notnull"])
        self.assertFalse(cols["AGENT_CODE"]["notnull"])
        self.assertTrue(cols["RENEWAL_COUNT"]["default"])
        self.assertIsNone(cols["RENEWAL_COUNT"]["len"])
        self.assertEqual(cols["POLICY_TYPE"]["raw"], "CHAR(3) NOT NULL")

    def test_parse_named_constraints(self):
        tables, _ = cs.parse(BASE_SQL)
        self.assertEqual(tables["ACMEINS.POLICIES"]["constraints"], {"PRIMARY KEY:PK_POLICIES", "CHECK:CK_TYPE"})
        self.assertEqual(tables["ACMEINS.NOTES"]["constraints"], set())

    def test_parse_sequences_normalised(self):
        _, seqs = cs.parse(BASE_SQL)
        self.assertEqual(seqs, {"ACMEINS.POLICY_SEQ": "START WITH 1 INCREMENT BY 1"})

    def test_parse_if_not_exists_and_case(self):
        tables, _ = cs.parse("create table if not exists s.t (a int, b char(2));")
        self.assertEqual(set(tables["S.T"]["cols"]), {"A", "B"})

    def test_parse_none_and_empty(self):
        self.assertEqual(cs.parse(None), ({}, {}))
        self.assertEqual(cs.parse(""), ({}, {}))

    def test_parse_repo_ddl(self):
        with open(os.path.join(ROOT, "sql/ddl/create-tables.sql")) as fh:
            tables, _ = cs.parse(fh.read())
        self.assertIn("ACMEINS.POLICIES", tables)
        self.assertIn("PRIMARY KEY:PK_POLICIES", tables["ACMEINS.POLICIES"]["constraints"])


class NarrowedTests(unittest.TestCase):
    def test_decimal_widening_is_fine(self):
        self.assertFalse(cs.narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 11, 2)))

    def test_decimal_scale_reduced(self):
        self.assertTrue(cs.narrowed(col("DECIMAL", 11, 4), col("DECIMAL", 11, 2)))

    def test_decimal_integer_digits_reduced(self):
        self.assertTrue(cs.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 11, 3)))

    def test_char_length(self):
        self.assertTrue(cs.narrowed(col("CHAR", 12), col("CHAR", 10)))
        self.assertFalse(cs.narrowed(col("CHAR", 10), col("CHAR", 12)))
        self.assertFalse(cs.narrowed(col("DATE"), col("DATE")))

    def test_integer_ranks(self):
        self.assertTrue(cs.narrowed(col("BIGINT"), col("INTEGER")))
        self.assertFalse(cs.narrowed(col("SMALLINT"), col("INT")))

    def test_char_varchar_switch(self):
        self.assertFalse(cs.narrowed(col("CHAR", 6), col("VARCHAR", 6)))
        self.assertTrue(cs.narrowed(col("VARCHAR", 6), col("CHAR", 4)))

    def test_unrelated_type_change(self):
        self.assertTrue(cs.narrowed(col("DECIMAL", 5, 0), col("INTEGER")))


class SchemaFilesTests(unittest.TestCase):
    def test_selects_ddl_and_schema_sql(self):
        files = ["sql/ddl/a.sql", "java-facade/src/main/resources/schema.sql",
                 "java-facade/src/main/resources/data.sql", "sql/readme.md", "other/x.sql"]
        self.assertEqual(cs.schema_files(lambda: files),
                         ["sql/ddl/a.sql", "java-facade/src/main/resources/schema.sql"])


class MainTests(unittest.TestCase):
    def run_main(self, base, head, migrations=None):
        """base/head: dict path -> sql (None = absent)."""
        migrations = migrations or {}
        base_all = {**base, **{p: b for p, (b, _) in migrations.items()}}
        head_all = {**head, **{p: h for p, (_, h) in migrations.items()}}
        captured = {}

        def fake_report(name, findings):
            captured["findings"] = findings
            return 1 if any(s == "FAIL" for s, _, _ in findings) else 0
        with mock.patch.object(cs, "files_at_base", lambda prefix="": [p for p in base_all if p.startswith(prefix)]), \
                mock.patch.object(cs, "files_at_head", lambda prefix="": [p for p in head_all if head_all[p] is not None]), \
                mock.patch.object(cs, "base_file", lambda p: base_all.get(p)), \
                mock.patch.object(cs, "head_file", lambda p: head_all.get(p)), \
                mock.patch.object(cs, "report", fake_report):
            code = cs.main()
        return code, {fid: sev for sev, fid, _ in captured["findings"]}

    def test_unchanged_schema_passes(self):
        code, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": BASE_SQL})
        self.assertEqual((code, f), (0, {}))

    def test_additive_nullable_column_is_note(self):
        new = BASE_SQL.replace("AGENT_CODE      VARCHAR(6),", "AGENT_CODE VARCHAR(6), BRANCH CHAR(4),")
        code, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": new})
        self.assertEqual(code, 0)
        self.assertEqual(f, {"schema:ACMEINS.POLICIES.BRANCH:column-added": "NOTE"})

    def test_new_not_null_column_without_default_fails(self):
        new = BASE_SQL.replace("AGENT_CODE      VARCHAR(6),", "AGENT_CODE VARCHAR(6), BRANCH CHAR(4) NOT NULL,")
        code, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": new})
        self.assertEqual(code, 1)
        self.assertEqual(f, {"schema:ACMEINS.POLICIES.BRANCH:new-required-column": "FAIL"})

    def test_new_not_null_column_with_default_is_note(self):
        new = BASE_SQL.replace("AGENT_CODE      VARCHAR(6),", "AGENT_CODE VARCHAR(6), BRANCH CHAR(4) NOT NULL DEFAULT 'X',")
        _, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": new})
        self.assertEqual(f, {"schema:ACMEINS.POLICIES.BRANCH:column-added": "NOTE"})

    def test_dropped_table_and_column(self):
        new = BASE_SQL.replace("CREATE TABLE ACMEINS.NOTES (\n    NOTE_ID INTEGER NOT NULL\n);", "")
        new = new.replace("    AGENT_CODE      VARCHAR(6),\n", "")
        _, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": new})
        self.assertEqual(f, {"schema:ACMEINS.NOTES:table-dropped": "FAIL",
                             "schema:ACMEINS.POLICIES.AGENT_CODE:column-dropped": "FAIL"})

    def test_narrowed_vs_changed_type_ids(self):
        new = BASE_SQL.replace("CHAR(12)      NOT NULL", "CHAR(10) NOT NULL")
        new = new.replace("TOTAL_PREMIUM   DECIMAL(11,2)", "TOTAL_PREMIUM VARCHAR(20)")
        _, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": new})
        self.assertEqual(f, {"schema:ACMEINS.POLICIES.POLICY_NUMBER:type-narrowed": "FAIL",
                             "schema:ACMEINS.POLICIES.TOTAL_PREMIUM:type-changed": "FAIL"})

    def test_made_not_null(self):
        new = BASE_SQL.replace("AGENT_CODE      VARCHAR(6),", "AGENT_CODE VARCHAR(6) NOT NULL,")
        _, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": new})
        self.assertEqual(f, {"schema:ACMEINS.POLICIES.AGENT_CODE:made-not-null": "FAIL"})

    def test_made_not_null_with_default_is_allowed(self):
        new = BASE_SQL.replace("AGENT_CODE      VARCHAR(6),", "AGENT_CODE VARCHAR(6) DEFAULT 'NONE' NOT NULL,")
        code, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": new})
        self.assertEqual((code, f), (0, {}))

    def test_constraint_removed(self):
        new = BASE_SQL.replace(",\n    CONSTRAINT CK_TYPE CHECK (POLICY_TYPE IN ('AUT', 'HOM'))", "")
        _, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": new})
        self.assertEqual(f, {"schema:ACMEINS.POLICIES:CHECK:CK_TYPE:constraint-removed": "FAIL"})

    def test_sequence_dropped_and_changed(self):
        dropped = BASE_SQL.replace("CREATE SEQUENCE ACMEINS.POLICY_SEQ START WITH 1 INCREMENT BY 1;", "")
        _, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": dropped})
        self.assertEqual(f, {"schema:ACMEINS.POLICY_SEQ:sequence-dropped": "FAIL"})
        changed = BASE_SQL.replace("INCREMENT BY 1;", "INCREMENT BY 10;")
        _, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": changed})
        self.assertEqual(f, {"schema:ACMEINS.POLICY_SEQ:sequence-changed": "FAIL"})

    def test_removed_schema_file(self):
        _, f = self.run_main({"sql/t.sql": BASE_SQL}, {"sql/t.sql": None})
        self.assertEqual(f, {"schema:sql/t.sql:file-removed": "FAIL"})

    def test_removed_file_without_tables_is_ignored(self):
        code, f = self.run_main({"sql/seed.sql": "INSERT INTO X VALUES (1);"}, {"sql/seed.sql": None})
        self.assertEqual((code, f), (0, {}))

    def test_applied_migration_edit_fails_but_new_migration_ok(self):
        mig = {"svc/src/main/resources/db/migration/V1__init.sql": ("CREATE TABLE A (X INT);", "CREATE TABLE A (X BIGINT);"),
               "svc/src/main/resources/db/migration/V2__ok.sql": ("SELECT 1;", "SELECT 1;")}
        _, f = self.run_main({}, {}, mig)
        self.assertEqual(f, {"schema:svc/src/main/resources/db/migration/V1__init.sql:applied-migration-modified": "FAIL"})


if __name__ == "__main__":
    unittest.main()
