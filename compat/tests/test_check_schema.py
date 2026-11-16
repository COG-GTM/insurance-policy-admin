import os
import unittest

from helpers import COMPAT_DIR, allowed_changes, fake_repo, run_quiet

import check_schema

SCHEMA = "java-facade/src/main/resources/schema.sql"
DDL = "sql/ddl/create-tables.sql"

BASE_SQL = """
-- base layout
CREATE TABLE IF NOT EXISTS ACMEINS.POLICIES (
    POLICY_NUMBER   CHAR(12)      NOT NULL,
    POLICY_TYPE     CHAR(3)       NOT NULL,
    AGENT_CODE      CHAR(6),
    TOTAL_PREMIUM   DECIMAL(11,2) DEFAULT 0,
    RENEWAL_COUNT   SMALLINT      DEFAULT 0,
    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER)
);
CREATE SEQUENCE ACMEINS.POLICY_SEQ START WITH 1000000 INCREMENT BY 1;
"""


def col(type_, len_=None, scale=None):
    return {"type": type_, "len": len_, "scale": scale}


class ParseTests(unittest.TestCase):
    def test_parses_columns_types_and_flags(self):
        tables, seqs = check_schema.parse(BASE_SQL)
        cols = tables["ACMEINS.POLICIES"]["cols"]
        self.assertEqual(list(cols), ["POLICY_NUMBER", "POLICY_TYPE", "AGENT_CODE", "TOTAL_PREMIUM", "RENEWAL_COUNT"])
        self.assertEqual(
            cols["POLICY_NUMBER"],
            {"type": "CHAR", "len": 12, "scale": None, "notnull": True, "default": False, "raw": "CHAR(12) NOT NULL"},
        )
        self.assertEqual((cols["TOTAL_PREMIUM"]["len"], cols["TOTAL_PREMIUM"]["scale"]), (11, 2))
        self.assertTrue(cols["TOTAL_PREMIUM"]["default"])
        self.assertFalse(cols["AGENT_CODE"]["notnull"])
        self.assertEqual(tables["ACMEINS.POLICIES"]["constraints"], {"PRIMARY KEY:PK_POLICIES"})
        self.assertEqual(seqs, {"ACMEINS.POLICY_SEQ": "START WITH 1000000 INCREMENT BY 1"})

    def test_strips_comments_and_ignores_unterminated_inputs(self):
        tables, _ = check_schema.parse("-- CREATE TABLE GHOST (A INT);\nCREATE TABLE real_t (A INT, B);")
        self.assertEqual(list(tables), ["REAL_T"])
        self.assertEqual(list(tables["REAL_T"]["cols"]), ["A"])
        self.assertEqual(check_schema.parse(None), ({}, {}))

    def test_collects_named_constraints_and_skips_unnamed(self):
        tables, _ = check_schema.parse(
            "CREATE TABLE T (A INT, B INT, CONSTRAINT FK_B FOREIGN KEY (B) REFERENCES X (Y),"
            " CONSTRAINT UQ_A UNIQUE (A), PRIMARY KEY (A, B), CHECK (A > 0))"
        )
        self.assertEqual(tables["T"]["constraints"], {"FOREIGN KEY:FK_B", "UNIQUE:UQ_A"})
        self.assertEqual(list(tables["T"]["cols"]), ["A", "B"])

    def test_parses_real_repo_schema(self):
        with open(os.path.join(os.path.dirname(COMPAT_DIR), SCHEMA)) as fh:
            tables, seqs = check_schema.parse(fh.read())
        self.assertIn("ACMEINS.COVERAGES", tables)
        self.assertEqual(tables["ACMEINS.POLICIES"]["cols"]["UPDATED_BY"]["raw"], "CHAR(8) NOT NULL DEFAULT 'SYSTEM'")
        self.assertIn("FOREIGN KEY:FK_POL_HOLDER", tables["ACMEINS.POLICIES"]["constraints"])
        self.assertEqual(len(seqs), 1)

    def test_split_top_respects_parentheses(self):
        self.assertEqual(check_schema.split_top("A DECIMAL(5,2), B CHAR(1) ,, "), ["A DECIMAL(5,2)", "B CHAR(1)"])


class NarrowedTests(unittest.TestCase):
    def test_decimal_precision_and_scale(self):
        self.assertFalse(check_schema.narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 11, 2)))
        self.assertTrue(check_schema.narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 9, 1)))
        self.assertTrue(check_schema.narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 9, 3)))  # fewer integer digits
        self.assertFalse(check_schema.narrowed(col("DECIMAL", 9, 2), col("DECIMAL", 10, 3)))

    def test_same_type_length(self):
        self.assertTrue(check_schema.narrowed(col("CHAR", 10), col("CHAR", 8)))
        self.assertFalse(check_schema.narrowed(col("CHAR", 10), col("CHAR", 12)))
        self.assertFalse(check_schema.narrowed(col("DATE"), col("DATE")))

    def test_integer_ranks(self):
        self.assertFalse(check_schema.narrowed(col("SMALLINT"), col("BIGINT")))
        self.assertFalse(check_schema.narrowed(col("INT"), col("INTEGER")))
        self.assertTrue(check_schema.narrowed(col("BIGINT"), col("INTEGER")))

    def test_char_varchar_and_incompatible_types(self):
        self.assertFalse(check_schema.narrowed(col("CHAR", 10), col("VARCHAR", 10)))
        self.assertTrue(check_schema.narrowed(col("VARCHAR", 40), col("CHAR", 20)))
        self.assertTrue(check_schema.narrowed(col("CHAR", 10), col("DATE")))
        self.assertTrue(check_schema.narrowed(col("DECIMAL", 9, 2), col("NUMERIC", 9, 2)))


class SchemaFilesTests(unittest.TestCase):
    def test_selects_ddl_and_resource_schema_only(self):
        files = [DDL, SCHEMA, "java-facade/src/main/resources/data.sql", "sql/README.md", "other/x.sql"]
        self.assertEqual(check_schema.schema_files(lambda: files), [DDL, SCHEMA])


class MainTests(unittest.TestCase):
    def run_main(self, base, head, allowed=()):
        with allowed_changes(allowed), fake_repo(check_schema, base, head):
            return run_quiet(check_schema.main)

    def ids(self, out):
        return [ln.split(None, 1)[1].split(": ", 1)[0] for ln in out.splitlines()[1:]]

    def test_unchanged_schema_passes(self):
        code, out = self.run_main({SCHEMA: BASE_SQL}, {SCHEMA: BASE_SQL})
        self.assertEqual(code, 0)
        self.assertIn("== DB schema compatibility: PASS", out)
        self.assertIn("no compatibility-relevant changes found", out)

    def test_additive_nullable_column_is_note_only(self):
        head = BASE_SQL.replace(
            "RENEWAL_COUNT   SMALLINT      DEFAULT 0,",
            "RENEWAL_COUNT   SMALLINT      DEFAULT 0,\n    NEW_FLAG CHAR(1),\n    NEW_REQ CHAR(1) NOT NULL DEFAULT 'N',",
        )
        code, out = self.run_main({SCHEMA: BASE_SQL}, {SCHEMA: head})
        self.assertEqual(code, 0)
        self.assertEqual(
            self.ids(out), ["schema:ACMEINS.POLICIES.NEW_FLAG:column-added", "schema:ACMEINS.POLICIES.NEW_REQ:column-added"]
        )

    def test_breaking_column_changes_fail(self):
        head = (
            BASE_SQL.replace("POLICY_TYPE     CHAR(3)       NOT NULL,\n", "")
            .replace("AGENT_CODE      CHAR(6),", "AGENT_CODE      CHAR(6) NOT NULL,")
            .replace("DECIMAL(11,2)", "DECIMAL(9,2)")
            .replace("RENEWAL_COUNT   SMALLINT", "RENEWAL_COUNT   DATE")
            .replace("    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER)\n", "    BRANCH CHAR(4) NOT NULL\n")
        )
        code, out = self.run_main({SCHEMA: BASE_SQL}, {SCHEMA: head})
        self.assertEqual(code, 1)
        self.assertEqual(
            sorted(self.ids(out)),
            sorted(
                [
                    "schema:ACMEINS.POLICIES.POLICY_TYPE:column-dropped",
                    "schema:ACMEINS.POLICIES.AGENT_CODE:made-not-null",
                    "schema:ACMEINS.POLICIES.TOTAL_PREMIUM:type-narrowed",
                    "schema:ACMEINS.POLICIES.RENEWAL_COUNT:type-changed",
                    "schema:ACMEINS.POLICIES.BRANCH:new-required-column",
                    "schema:ACMEINS.POLICIES:PRIMARY KEY:PK_POLICIES:constraint-removed",
                ]
            ),
        )
        self.assertIn("DECIMAL(11,2) DEFAULT 0 -> DECIMAL(9,2) DEFAULT 0", out)

    def test_not_null_with_default_is_compatible(self):
        head = BASE_SQL.replace("AGENT_CODE      CHAR(6),", "AGENT_CODE      CHAR(6) NOT NULL DEFAULT 'NONE',")
        code, out = self.run_main({SCHEMA: BASE_SQL}, {SCHEMA: head})
        self.assertEqual(code, 0, out)

    def test_dropped_table_and_sequences(self):
        head = "CREATE TABLE OTHER (A INT);"
        code, out = self.run_main({SCHEMA: BASE_SQL}, {SCHEMA: head})
        self.assertEqual(code, 1)
        self.assertEqual(self.ids(out), ["schema:ACMEINS.POLICIES:table-dropped", "schema:ACMEINS.POLICY_SEQ:sequence-dropped"])

    def test_changed_sequence_fails(self):
        head = BASE_SQL.replace("INCREMENT BY 1", "INCREMENT BY 10")
        code, out = self.run_main({SCHEMA: BASE_SQL}, {SCHEMA: head})
        self.assertEqual(code, 1)
        self.assertIn("schema:ACMEINS.POLICY_SEQ:sequence-changed", out)
        self.assertIn("-> 'START WITH 1000000 INCREMENT BY 10'", out)

    def test_removed_schema_file(self):
        code, out = self.run_main({SCHEMA: BASE_SQL, DDL: "-- only comments"}, {})
        self.assertEqual(code, 1)
        self.assertEqual(self.ids(out), [f"schema:{SCHEMA}:file-removed"])
        self.assertIn("(1 tables)", out)

    def test_applied_flyway_migration_is_immutable(self):
        mig = "svc/src/main/resources/db/migration/V1__init.sql"
        new_mig = "svc/src/main/resources/db/migration/V2__more.sql"
        code, out = self.run_main({mig: "CREATE TABLE A (X INT);"}, {mig: "CREATE TABLE A (X BIGINT);", new_mig: "x"})
        self.assertEqual(code, 1)
        self.assertEqual(self.ids(out), [f"schema:{mig}:applied-migration-modified"])
        code, _ = self.run_main({mig: "same"}, {mig: "same"})
        self.assertEqual(code, 0)

    def test_allow_listed_finding_is_approved(self):
        head = BASE_SQL.replace("    AGENT_CODE      CHAR(6),\n", "")
        fid = "schema:ACMEINS.POLICIES.AGENT_CODE:column-dropped"
        code, out = self.run_main(
            {SCHEMA: BASE_SQL}, {SCHEMA: head}, [{"id": fid, "ticket": "AB-600", "reason": "column retired"}]
        )
        self.assertEqual(code, 0)
        self.assertIn(f"APPROVED  {fid}", out)
        self.assertIn("(ticket AB-600: column retired)", out)


if __name__ == "__main__":
    unittest.main()
