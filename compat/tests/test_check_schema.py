import os
import unittest
from unittest import mock

from support import captured_findings, ids

import check_schema

BASE_DDL = """
-- policy master
CREATE TABLE IF NOT EXISTS ACMEINS.POLICIES (
    POLICY_NUMBER   CHAR(12)       NOT NULL,
    POLICY_TYPE     CHAR(3)        NOT NULL,
    TOTAL_PREMIUM   DECIMAL(11,2)  DEFAULT 0,
    RENEWAL_COUNT   SMALLINT       DEFAULT 0,
    AGENT_CODE      CHAR(6),
    NOTES           VARCHAR(100),
    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER)
);
CREATE TABLE ACMEINS.COVERAGES (
    POLICY_NUMBER   CHAR(12) NOT NULL,
    CONSTRAINT FK_COV_POLICY FOREIGN KEY (POLICY_NUMBER)
        REFERENCES ACMEINS.POLICIES (POLICY_NUMBER)
);
CREATE SEQUENCE ACMEINS.POLICY_SEQ START WITH 1000000 INCREMENT BY 1;
"""


def col(type_, length=None, scale=None, notnull=False, default=False):
    return {"type": type_, "len": length, "scale": scale, "notnull": notnull, "default": default, "raw": type_}


class StripAndSplitTests(unittest.TestCase):
    def test_strip_comments_removes_line_comments_only(self):
        self.assertEqual(check_schema.strip_comments("A INT, -- note\nB INT"), "A INT, \nB INT")

    def test_split_top_ignores_commas_inside_parentheses(self):
        parts = check_schema.split_top(" A DECIMAL(11,2), B CHAR(3) ,CONSTRAINT PK PRIMARY KEY (A, B) ")
        self.assertEqual(parts, ["A DECIMAL(11,2)", "B CHAR(3)", "CONSTRAINT PK PRIMARY KEY (A, B)"])

    def test_split_top_drops_empty_parts(self):
        self.assertEqual(check_schema.split_top("A INT,, ,"), ["A INT"])


class ParseTests(unittest.TestCase):
    def setUp(self):
        self.tables, self.seqs = check_schema.parse(BASE_DDL)

    def test_parses_qualified_table_names_uppercased(self):
        self.assertEqual(set(self.tables), {"ACMEINS.POLICIES", "ACMEINS.COVERAGES"})

    def test_parses_column_types_lengths_and_scales(self):
        cols = self.tables["ACMEINS.POLICIES"]["cols"]
        self.assertEqual(cols["POLICY_NUMBER"]["type"], "CHAR")
        self.assertEqual(cols["POLICY_NUMBER"]["len"], 12)
        self.assertIsNone(cols["POLICY_NUMBER"]["scale"])
        self.assertEqual((cols["TOTAL_PREMIUM"]["type"], cols["TOTAL_PREMIUM"]["len"], cols["TOTAL_PREMIUM"]["scale"]), ("DECIMAL", 11, 2))
        self.assertEqual((cols["RENEWAL_COUNT"]["type"], cols["RENEWAL_COUNT"]["len"]), ("SMALLINT", None))

    def test_parses_not_null_and_default_flags(self):
        cols = self.tables["ACMEINS.POLICIES"]["cols"]
        self.assertTrue(cols["POLICY_NUMBER"]["notnull"])
        self.assertFalse(cols["POLICY_NUMBER"]["default"])
        self.assertFalse(cols["TOTAL_PREMIUM"]["notnull"])
        self.assertTrue(cols["TOTAL_PREMIUM"]["default"])

    def test_constraint_lines_are_not_columns(self):
        self.assertNotIn("CONSTRAINT", self.tables["ACMEINS.POLICIES"]["cols"])
        self.assertEqual(self.tables["ACMEINS.POLICIES"]["constraints"], {"PRIMARY KEY:PK_POLICIES"})
        self.assertEqual(self.tables["ACMEINS.COVERAGES"]["constraints"], {"FOREIGN KEY:FK_COV_POLICY"})

    def test_parses_sequences_with_normalised_options(self):
        self.assertEqual(self.seqs, {"ACMEINS.POLICY_SEQ": "START WITH 1000000 INCREMENT BY 1"})

    def test_none_or_empty_sql_yields_nothing(self):
        self.assertEqual(check_schema.parse(None), ({}, {}))
        self.assertEqual(check_schema.parse(""), ({}, {}))

    def test_commented_out_table_is_ignored(self):
        tables, _ = check_schema.parse("-- CREATE TABLE OLD (A INT);\nCREATE TABLE NEW (A INT);")
        self.assertEqual(set(tables), {"NEW"})


class NarrowedTests(unittest.TestCase):
    def test_same_char_type_shorter_is_narrowed_longer_is_not(self):
        self.assertTrue(check_schema.narrowed(col("CHAR", 12), col("CHAR", 10)))
        self.assertFalse(check_schema.narrowed(col("CHAR", 12), col("CHAR", 14)))
        self.assertFalse(check_schema.narrowed(col("CHAR", 12), col("CHAR", 12)))

    def test_decimal_scale_reduction_is_narrowed(self):
        self.assertTrue(check_schema.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 11, 1)))

    def test_decimal_integer_digit_reduction_is_narrowed(self):
        # 11,2 has 9 integer digits; 11,4 only 7.
        self.assertTrue(check_schema.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 11, 4)))

    def test_decimal_widening_is_not_narrowed(self):
        self.assertFalse(check_schema.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 13, 2)))
        self.assertFalse(check_schema.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 12, 3)))

    def test_integer_rank_changes(self):
        self.assertTrue(check_schema.narrowed(col("INTEGER"), col("SMALLINT")))
        self.assertFalse(check_schema.narrowed(col("SMALLINT"), col("BIGINT")))
        self.assertFalse(check_schema.narrowed(col("INT"), col("INTEGER")))

    def test_char_varchar_swap_compares_lengths(self):
        self.assertFalse(check_schema.narrowed(col("CHAR", 10), col("VARCHAR", 10)))
        self.assertTrue(check_schema.narrowed(col("VARCHAR", 40), col("CHAR", 30)))

    def test_unrelated_type_change_is_treated_as_narrowing(self):
        self.assertTrue(check_schema.narrowed(col("DECIMAL", 11, 2), col("VARCHAR", 20)))
        self.assertTrue(check_schema.narrowed(col("DATE"), col("TIMESTAMP")))


class MainTests(unittest.TestCase):
    PATH = "sql/ddl/create-tables.sql"

    def run_main(self, base_files, head_files):
        """base_files/head_files: path -> sql (head value None means deleted)."""
        with mock.patch.object(check_schema, "files_at_base", side_effect=lambda prefix="": list(base_files)), \
                mock.patch.object(check_schema, "base_file", side_effect=base_files.get), \
                mock.patch.object(check_schema, "head_file", side_effect=head_files.get), \
                captured_findings(check_schema) as findings:
            code = check_schema.main()
        return code, findings

    def run_ddl(self, new_ddl):
        return self.run_main({self.PATH: BASE_DDL}, {self.PATH: new_ddl})

    def test_unchanged_schema_passes(self):
        code, findings = self.run_ddl(BASE_DDL)
        self.assertEqual(code, 0)
        self.assertEqual(findings, [])

    def test_dropped_column_fails(self):
        code, findings = self.run_ddl(BASE_DDL.replace("    NOTES           VARCHAR(100),\n", ""))
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {"schema:ACMEINS.POLICIES.NOTES:column-dropped"})

    def test_dropped_table_fails(self):
        new = BASE_DDL[:BASE_DDL.index("CREATE TABLE ACMEINS.COVERAGES")] + BASE_DDL[BASE_DDL.index("CREATE SEQUENCE"):]
        code, findings = self.run_ddl(new)
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {"schema:ACMEINS.COVERAGES:table-dropped"})

    def test_narrowed_column_fails_with_type_narrowed_id(self):
        _, findings = self.run_ddl(BASE_DDL.replace("POLICY_NUMBER   CHAR(12)       NOT NULL", "POLICY_NUMBER   CHAR(10)       NOT NULL"))
        self.assertEqual(ids(findings, "FAIL"), {"schema:ACMEINS.POLICIES.POLICY_NUMBER:type-narrowed"})
        msg = findings[0][2]
        self.assertIn("CHAR(12) NOT NULL -> CHAR(10) NOT NULL", msg)

    def test_integer_downgrade_is_type_narrowed(self):
        _, findings = self.run_main({self.PATH: "CREATE TABLE T (A INTEGER);"}, {self.PATH: "CREATE TABLE T (A SMALLINT);"})
        self.assertEqual(ids(findings), {"schema:T.A:type-narrowed"})

    def test_cross_family_type_change_is_type_changed(self):
        _, findings = self.run_ddl(BASE_DDL.replace("DECIMAL(11,2)  DEFAULT 0", "VARCHAR(20)    DEFAULT '0'"))
        self.assertEqual(ids(findings), {"schema:ACMEINS.POLICIES.TOTAL_PREMIUM:type-changed"})

    def test_widened_column_passes(self):
        code, findings = self.run_ddl(BASE_DDL.replace("VARCHAR(100)", "VARCHAR(200)"))
        self.assertEqual((code, findings), (0, []))

    def test_making_nullable_column_not_null_without_default_fails(self):
        _, findings = self.run_ddl(BASE_DDL.replace("AGENT_CODE      CHAR(6),", "AGENT_CODE      CHAR(6) NOT NULL,"))
        self.assertEqual(ids(findings), {"schema:ACMEINS.POLICIES.AGENT_CODE:made-not-null"})

    def test_making_column_not_null_with_default_passes(self):
        code, findings = self.run_ddl(BASE_DDL.replace("AGENT_CODE      CHAR(6),", "AGENT_CODE      CHAR(6) NOT NULL DEFAULT 'NONE',"))
        self.assertEqual((code, findings), (0, []))

    def test_new_required_column_fails_but_nullable_addition_is_a_note(self):
        new = BASE_DDL.replace("    NOTES ", "    WEB_FLAG CHAR(1) NOT NULL,\n    CHANNEL CHAR(3),\n    NOTES ")
        code, findings = self.run_ddl(new)
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings, "FAIL"), {"schema:ACMEINS.POLICIES.WEB_FLAG:new-required-column"})
        self.assertEqual(ids(findings, "NOTE"), {"schema:ACMEINS.POLICIES.CHANNEL:column-added"})

    def test_new_not_null_column_with_default_is_only_a_note(self):
        code, findings = self.run_ddl(BASE_DDL.replace("    NOTES ", "    WEB_FLAG CHAR(1) NOT NULL DEFAULT 'N',\n    NOTES "))
        self.assertEqual(code, 0)
        self.assertEqual(ids(findings, "NOTE"), {"schema:ACMEINS.POLICIES.WEB_FLAG:column-added"})

    def test_removed_named_constraint_fails(self):
        new = BASE_DDL.replace(",\n    CONSTRAINT FK_COV_POLICY FOREIGN KEY (POLICY_NUMBER)\n        REFERENCES ACMEINS.POLICIES (POLICY_NUMBER)", "")
        _, findings = self.run_ddl(new)
        self.assertEqual(ids(findings), {"schema:ACMEINS.COVERAGES:FOREIGN KEY:FK_COV_POLICY:constraint-removed"})

    def test_sequence_dropped_and_changed(self):
        _, findings = self.run_ddl(BASE_DDL.replace("CREATE SEQUENCE ACMEINS.POLICY_SEQ START WITH 1000000 INCREMENT BY 1;", ""))
        self.assertEqual(ids(findings), {"schema:ACMEINS.POLICY_SEQ:sequence-dropped"})
        _, findings = self.run_ddl(BASE_DDL.replace("START WITH 1000000", "START WITH 1"))
        self.assertEqual(ids(findings), {"schema:ACMEINS.POLICY_SEQ:sequence-changed"})
        self.assertIn("'START WITH 1000000 INCREMENT BY 1' -> 'START WITH 1 INCREMENT BY 1'", findings[0][2])

    def test_removed_schema_file_with_tables_fails(self):
        code, findings = self.run_main({self.PATH: BASE_DDL}, {})
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {f"schema:{self.PATH}:file-removed"})
        self.assertIn("2 tables", findings[0][2])

    def test_removed_schema_file_without_tables_is_ignored(self):
        code, findings = self.run_main({self.PATH: "-- nothing here"}, {})
        self.assertEqual((code, findings), (0, []))

    def test_only_schema_paths_are_compared(self):
        files = {"docs/notes.sql": BASE_DDL, "java-facade/src/main/resources/data.sql": BASE_DDL}
        code, findings = self.run_main(files, {})
        self.assertEqual((code, findings), (0, []))
        self.assertEqual(check_schema.schema_files(lambda: ["sql/a.sql", "x/src/main/resources/schema.sql", "x/src/main/resources/data.sql", "sql/readme.md"]),
                         ["sql/a.sql", "x/src/main/resources/schema.sql"])

    def test_edited_applied_flyway_migration_fails(self):
        mig = "svc/src/main/resources/db/migration/V1__init.sql"
        code, findings = self.run_main({mig: "CREATE TABLE A (X INT);"}, {mig: "CREATE TABLE A (X INT, Y INT);"})
        self.assertEqual(code, 1)
        self.assertEqual(ids(findings), {f"schema:{mig}:applied-migration-modified"})

    def test_unchanged_flyway_migration_passes(self):
        mig = "svc/src/main/resources/db/migration/V1__init.sql"
        code, findings = self.run_main({mig: "CREATE TABLE A (X INT);"}, {mig: "CREATE TABLE A (X INT);"})
        self.assertEqual((code, findings), (0, []))


class RealDdlTests(unittest.TestCase):
    def test_repository_ddl_parses_core_tables(self):
        with open(os.path.join(os.path.dirname(check_schema.__file__), "..", "sql", "ddl", "create-tables.sql")) as fh:
            tables, _ = check_schema.parse(fh.read())
        policies = next(t for name, t in tables.items() if name.endswith("POLICIES"))
        self.assertEqual(policies["cols"]["POLICY_NUMBER"]["len"], 12)


if __name__ == "__main__":
    unittest.main()
