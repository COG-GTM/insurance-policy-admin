import os
import unittest
from unittest import mock

import check_schema
from check_schema import narrowed, parse, schema_files, split_top, strip_comments

from tests import support

BASE_DDL = """
-- core tables
CREATE TABLE ACMEINS.POLICIES (
    POLICY_NUMBER   CHAR(12)       NOT NULL,
    POLICY_STATUS   CHAR(2)        NOT NULL DEFAULT 'PN',
    TOTAL_PREMIUM   DECIMAL(11,2)  DEFAULT 0,
    AGENT_CODE      CHAR(6),
    RISK_SCORE      SMALLINT,
    EFFECTIVE_DATE  DATE           NOT NULL,
    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER)
);
CREATE TABLE ACMEINS.COVERAGES (
    POLICY_NUMBER   CHAR(12)       NOT NULL,
    SEQUENCE_NUM    SMALLINT       NOT NULL
);
CREATE SEQUENCE ACMEINS.POLICY_SEQ START WITH 1 INCREMENT BY 1;
"""


def col(type_, length=None, scale=None, notnull=False, default=False):
    return {
        "type": type_,
        "len": length,
        "scale": scale,
        "notnull": notnull,
        "default": default,
        "raw": type_,
    }


class ParsingTests(unittest.TestCase):
    def test_strip_comments_removes_line_comments_only(self):
        self.assertEqual(strip_comments("A INT, -- note\nB INT"), "A INT, \nB INT")

    def test_split_top_ignores_commas_inside_parentheses(self):
        self.assertEqual(
            split_top(
                "A DECIMAL(9, 2) NOT NULL, B CHAR(3),\n  CONSTRAINT PK PRIMARY KEY (A, B)"
            ),
            [
                "A DECIMAL(9, 2) NOT NULL",
                "B CHAR(3)",
                "CONSTRAINT PK PRIMARY KEY (A, B)",
            ],
        )

    def test_parse_extracts_columns_with_type_length_scale_and_flags(self):
        tables, _ = parse(BASE_DDL)
        cols = tables["ACMEINS.POLICIES"]["cols"]
        self.assertEqual(cols["POLICY_NUMBER"]["type"], "CHAR")
        self.assertEqual(cols["POLICY_NUMBER"]["len"], 12)
        self.assertTrue(cols["POLICY_NUMBER"]["notnull"])
        self.assertFalse(cols["POLICY_NUMBER"]["default"])
        self.assertEqual(
            (cols["TOTAL_PREMIUM"]["len"], cols["TOTAL_PREMIUM"]["scale"]), (11, 2)
        )
        self.assertTrue(cols["TOTAL_PREMIUM"]["default"])
        self.assertFalse(cols["AGENT_CODE"]["notnull"])
        self.assertIsNone(cols["EFFECTIVE_DATE"]["len"])
        self.assertTrue(
            cols["POLICY_STATUS"]["notnull"] and cols["POLICY_STATUS"]["default"]
        )

    def test_parse_records_named_constraints_but_not_as_columns(self):
        tables, _ = parse(BASE_DDL)
        policies = tables["ACMEINS.POLICIES"]
        self.assertEqual(policies["constraints"], {"PRIMARY KEY:PK_POLICIES"})
        self.assertNotIn("CONSTRAINT", policies["cols"])
        self.assertEqual(set(tables), {"ACMEINS.POLICIES", "ACMEINS.COVERAGES"})

    def test_parse_handles_if_not_exists_lowercase_and_spaced_precision(self):
        tables, _ = parse(
            "create table if not exists pas.t (amt decimal( 9 , 2 ) not null);"
        )
        amt = tables["PAS.T"]["cols"]["AMT"]
        self.assertEqual(
            (amt["type"], amt["len"], amt["scale"], amt["notnull"]),
            ("DECIMAL", 9, 2, True),
        )

    def test_parse_skips_column_entries_without_a_type(self):
        tables, _ = parse("CREATE TABLE T (A INT, ORPHAN, B CHAR(1));")
        self.assertEqual(list(tables["T"]["cols"]), ["A", "B"])

    def test_parse_normalises_sequence_options(self):
        _, seqs = parse(BASE_DDL)
        self.assertEqual(seqs, {"ACMEINS.POLICY_SEQ": "START WITH 1 INCREMENT BY 1"})

    def test_parse_ignores_commented_out_tables_and_none_input(self):
        self.assertEqual(parse("-- CREATE TABLE X (A INT);"), ({}, {}))
        self.assertEqual(parse(None), ({}, {}))


class NarrowedTests(unittest.TestCase):
    def test_char_length_reduction_is_narrowing(self):
        self.assertTrue(narrowed(col("CHAR", 10), col("CHAR", 8)))
        self.assertFalse(narrowed(col("CHAR", 10), col("CHAR", 12)))

    def test_same_type_without_length_is_not_narrowing(self):
        self.assertFalse(narrowed(col("DATE"), col("DATE")))

    def test_decimal_scale_or_integer_digits_reduction_is_narrowing(self):
        self.assertTrue(narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 11, 1)))
        self.assertTrue(narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 11, 4)))
        self.assertFalse(narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 13, 4)))
        self.assertFalse(narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 11, 2)))

    def test_integer_rank_changes(self):
        self.assertTrue(narrowed(col("INTEGER"), col("SMALLINT")))
        self.assertFalse(narrowed(col("SMALLINT"), col("BIGINT")))
        self.assertFalse(narrowed(col("INT"), col("INTEGER")))

    def test_char_varchar_interchange_compares_lengths(self):
        self.assertFalse(narrowed(col("CHAR", 10), col("VARCHAR", 10)))
        self.assertTrue(narrowed(col("VARCHAR", 10), col("CHAR", 5)))

    def test_unrelated_type_change_is_incompatible(self):
        self.assertTrue(narrowed(col("DATE"), col("TIMESTAMP")))
        self.assertTrue(narrowed(col("DECIMAL", 9, 2), col("INTEGER")))


class SchemaFilesTests(unittest.TestCase):
    def test_selects_sql_ddl_and_resource_schema_files_only(self):
        files = [
            "sql/ddl/create-tables.sql",
            "java-facade/src/main/resources/schema.sql",
            "java-facade/src/main/resources/data.sql",
            "sql/README.md",
            "other/schema.sql",
        ]
        self.assertEqual(schema_files(lambda: files), files[:2])


class MainTests(unittest.TestCase):
    PATH = "sql/ddl/create-tables.sql"

    def run_main(self, base, head, base_files=None):
        base_files = base_files or {self.PATH: base}
        head_files = {self.PATH: head} if head is not None else {}
        cap = support.FindingsCapture()
        with (
            mock.patch.object(
                check_schema, "files_at_base", lambda prefix="": list(base_files)
            ),
            mock.patch.object(
                check_schema, "files_at_head", lambda prefix="": list(head_files)
            ),
            mock.patch.object(check_schema, "base_file", base_files.get),
            mock.patch.object(check_schema, "head_file", head_files.get),
            mock.patch.object(check_schema, "report", cap),
        ):
            rc = check_schema.main()
        return rc, cap

    def test_unchanged_schema_has_no_findings(self):
        rc, cap = self.run_main(BASE_DDL, BASE_DDL)
        self.assertEqual((rc, cap.findings), (0, []))
        self.assertEqual(cap.check_name, "DB schema compatibility")

    def test_dropped_table_and_column_fail(self):
        head = BASE_DDL.replace("    AGENT_CODE      CHAR(6),\n", "")
        head = (
            head[: head.index("CREATE TABLE ACMEINS.COVERAGES")]
            + "CREATE SEQUENCE ACMEINS.POLICY_SEQ START WITH 1 INCREMENT BY 1;"
        )
        rc, cap = self.run_main(BASE_DDL, head)
        self.assertEqual(rc, 1)
        self.assertCountEqual(
            cap.ids("FAIL"),
            [
                "schema:ACMEINS.POLICIES.AGENT_CODE:column-dropped",
                "schema:ACMEINS.COVERAGES:table-dropped",
            ],
        )

    def test_narrowed_column_is_reported_as_type_narrowed(self):
        rc, cap = self.run_main(BASE_DDL, BASE_DDL.replace("CHAR(6),", "CHAR(4),"))
        self.assertEqual(rc, 1)
        self.assertEqual(
            cap.ids(), ["schema:ACMEINS.POLICIES.AGENT_CODE:type-narrowed"]
        )
        self.assertIn("CHAR(6) -> CHAR(4)", cap.findings[0][2])

    def test_integer_rank_reduction_is_reported_as_type_narrowed(self):
        rc, cap = self.run_main(
            BASE_DDL.replace("RISK_SCORE      SMALLINT", "RISK_SCORE      BIGINT"),
            BASE_DDL,
        )
        self.assertEqual(rc, 1)
        self.assertEqual(
            cap.ids(), ["schema:ACMEINS.POLICIES.RISK_SCORE:type-narrowed"]
        )

    def test_cross_family_change_is_reported_as_type_changed(self):
        rc, cap = self.run_main(
            BASE_DDL,
            BASE_DDL.replace("EFFECTIVE_DATE  DATE", "EFFECTIVE_DATE  TIMESTAMP"),
        )
        self.assertEqual(rc, 1)
        self.assertEqual(
            cap.ids(), ["schema:ACMEINS.POLICIES.EFFECTIVE_DATE:type-changed"]
        )

    def test_widening_is_allowed(self):
        rc, cap = self.run_main(
            BASE_DDL,
            BASE_DDL.replace("DECIMAL(11,2)", "DECIMAL(13,2)").replace(
                "CHAR(6),", "VARCHAR(10),"
            ),
        )
        self.assertEqual((rc, cap.findings), (0, []))

    def test_making_nullable_column_not_null_fails_unless_defaulted(self):
        rc, cap = self.run_main(
            BASE_DDL, BASE_DDL.replace("CHAR(6),", "CHAR(6) NOT NULL,")
        )
        self.assertEqual(
            cap.ids(), ["schema:ACMEINS.POLICIES.AGENT_CODE:made-not-null"]
        )
        rc, cap = self.run_main(
            BASE_DDL, BASE_DDL.replace("CHAR(6),", "CHAR(6) NOT NULL DEFAULT 'NONE',")
        )
        self.assertEqual((rc, cap.findings), (0, []))

    def test_new_columns_required_vs_additive(self):
        head = BASE_DDL.replace(
            "    AGENT_CODE ",
            "    REGION CHAR(2) NOT NULL,\n    CHANNEL CHAR(1) NOT NULL DEFAULT 'W',\n"
            "    NOTES VARCHAR(100),\n    AGENT_CODE ",
        )
        rc, cap = self.run_main(BASE_DDL, head)
        self.assertEqual(rc, 1)
        self.assertEqual(
            cap.ids("FAIL"), ["schema:ACMEINS.POLICIES.REGION:new-required-column"]
        )
        self.assertCountEqual(
            cap.ids("NOTE"),
            [
                "schema:ACMEINS.POLICIES.CHANNEL:column-added",
                "schema:ACMEINS.POLICIES.NOTES:column-added",
            ],
        )

    def test_removed_named_constraint_fails(self):
        _, cap = self.run_main(
            BASE_DDL,
            BASE_DDL.replace(
                ",\n    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER)", ""
            ),
        )
        self.assertEqual(
            cap.ids(),
            ["schema:ACMEINS.POLICIES:PRIMARY KEY:PK_POLICIES:constraint-removed"],
        )

    def test_sequence_dropped_or_changed_fails(self):
        _, cap = self.run_main(
            BASE_DDL, BASE_DDL.replace("INCREMENT BY 1", "INCREMENT BY 10")
        )
        self.assertEqual(cap.ids(), ["schema:ACMEINS.POLICY_SEQ:sequence-changed"])
        self.assertIn(
            "'START WITH 1 INCREMENT BY 1' -> 'START WITH 1 INCREMENT BY 10'",
            cap.findings[0][2],
        )
        _, cap = self.run_main(
            BASE_DDL,
            BASE_DDL.replace(
                "CREATE SEQUENCE ACMEINS.POLICY_SEQ START WITH 1 INCREMENT BY 1;", ""
            ),
        )
        self.assertEqual(cap.ids(), ["schema:ACMEINS.POLICY_SEQ:sequence-dropped"])

    def test_removed_schema_file_fails_only_if_it_defined_tables(self):
        rc, cap = self.run_main(BASE_DDL, None)
        self.assertEqual(rc, 1)
        self.assertEqual(cap.ids(), [f"schema:{self.PATH}:file-removed"])
        self.assertIn("(2 tables)", cap.findings[0][2])
        rc, cap = self.run_main("-- empty", None)
        self.assertEqual((rc, cap.findings), (0, []))

    def test_editing_applied_flyway_migration_fails(self):
        mig = "svc/src/main/resources/db/migration/V1__init.sql"
        base_files = {mig: "CREATE TABLE T (A INT);"}
        cap = support.FindingsCapture()
        with (
            mock.patch.object(
                check_schema, "files_at_base", lambda prefix="": list(base_files)
            ),
            mock.patch.object(check_schema, "base_file", base_files.get),
            mock.patch.object(
                check_schema, "head_file", lambda p: "CREATE TABLE T (A INT, B INT);"
            ),
            mock.patch.object(check_schema, "report", cap),
        ):
            self.assertEqual(check_schema.main(), 1)
        self.assertEqual(cap.ids(), [f"schema:{mig}:applied-migration-modified"])

    def test_unchanged_flyway_migration_passes(self):
        mig = "svc/src/main/resources/db/migration/V1__init.sql"
        cap = support.FindingsCapture()
        with (
            mock.patch.object(check_schema, "files_at_base", lambda prefix="": [mig]),
            mock.patch.object(check_schema, "base_file", lambda p: "X"),
            mock.patch.object(check_schema, "head_file", lambda p: "X"),
            mock.patch.object(check_schema, "report", cap),
        ):
            self.assertEqual(check_schema.main(), 0)
        self.assertEqual(cap.findings, [])

    def test_repository_schema_parses_core_tables(self):
        with open(
            os.path.join(support.REPO_ROOT, "sql", "ddl", "create-tables.sql")
        ) as fh:
            tables, seqs = parse(fh.read())
        self.assertIn("ACMEINS.POLICIES", tables)
        self.assertIn("API_FLAG", tables["ACMEINS.POLICIES"]["cols"])
        self.assertIn("ACMEINS.POLICY_SEQ", seqs)


if __name__ == "__main__":
    unittest.main()
