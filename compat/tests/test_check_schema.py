import check_schema as cs

BASE_DDL = """
-- core tables
CREATE TABLE ACMEINS.POLICIES (
    POLICY_NUMBER   CHAR(12)       NOT NULL,
    POLICY_STATUS   CHAR(2)        NOT NULL DEFAULT 'PN',
    AGENT_CODE      CHAR(6),
    TOTAL_PREMIUM   DECIMAL(11,2)  DEFAULT 0,
    RENEWAL_COUNT   INTEGER,
    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER)
);
CREATE TABLE ACMEINS.COVERAGES (
    POLICY_NUMBER   CHAR(12)       NOT NULL,
    SEQUENCE_NUM    SMALLINT       NOT NULL,
    CONSTRAINT FK_COV FOREIGN KEY (POLICY_NUMBER) REFERENCES ACMEINS.POLICIES
);
CREATE SEQUENCE ACMEINS.POLICY_SEQ AS INTEGER START WITH 1000000 INCREMENT BY 1;
"""


def col(type_, length=None, scale=None):
    return {"type": type_, "len": length, "scale": scale}


# --- parse -------------------------------------------------------------------

def test_parse_tables_columns_and_sequences():
    tables, seqs = cs.parse(BASE_DDL)
    assert set(tables) == {"ACMEINS.POLICIES", "ACMEINS.COVERAGES"}
    pol = tables["ACMEINS.POLICIES"]
    assert list(pol["cols"]) == ["POLICY_NUMBER", "POLICY_STATUS", "AGENT_CODE", "TOTAL_PREMIUM", "RENEWAL_COUNT"]
    assert pol["cols"]["TOTAL_PREMIUM"] == {
        "type": "DECIMAL", "len": 11, "scale": 2, "notnull": False, "default": True, "raw": "DECIMAL(11,2) DEFAULT 0"}
    assert pol["cols"]["POLICY_STATUS"]["notnull"] and pol["cols"]["POLICY_STATUS"]["default"]
    assert pol["cols"]["AGENT_CODE"]["notnull"] is False
    assert pol["cols"]["RENEWAL_COUNT"]["len"] is None
    assert pol["constraints"] == {"PRIMARY KEY:PK_POLICIES"}
    assert tables["ACMEINS.COVERAGES"]["constraints"] == {"FOREIGN KEY:FK_COV"}
    assert seqs == {"ACMEINS.POLICY_SEQ": "AS INTEGER START WITH 1000000 INCREMENT BY 1"}


def test_parse_ignores_comments_and_handles_if_not_exists_and_case():
    sql = """create table if not exists acmeins.t (  -- trailing ( comment
        id integer not null, -- CREATE TABLE FAKE (X INT)
        note varchar(40)
    );"""
    tables, _ = cs.parse(sql)
    assert list(tables) == ["ACMEINS.T"]
    assert tables["ACMEINS.T"]["cols"]["NOTE"]["type"] == "VARCHAR"
    assert tables["ACMEINS.T"]["cols"]["ID"]["notnull"] is True


def test_parse_empty_or_none_returns_nothing():
    assert cs.parse(None) == ({}, {})
    assert cs.parse("") == ({}, {})


def test_split_top_respects_nested_parentheses():
    assert cs.split_top("A DECIMAL(9,2), B CHAR(1) ,, C INT") == ["A DECIMAL(9,2)", "B CHAR(1)", "C INT"]


def test_strip_comments_removes_line_comments_only():
    assert cs.strip_comments("A INT, -- gone\nB INT") == "A INT, \nB INT"


# --- narrowed ----------------------------------------------------------------

def test_narrowed_char_length():
    assert cs.narrowed(col("CHAR", 10), col("CHAR", 8))
    assert not cs.narrowed(col("CHAR", 10), col("CHAR", 12))
    assert not cs.narrowed(col("DATE"), col("DATE"))


def test_narrowed_decimal_scale_and_integer_digits():
    assert not cs.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 13, 2))
    assert cs.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 11, 1))
    # same precision, more scale loses an integer digit
    assert cs.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 11, 3))
    assert not cs.narrowed(col("DECIMAL", 11, 2), col("DECIMAL", 12, 3))


def test_narrowed_integer_ranks():
    assert not cs.narrowed(col("SMALLINT"), col("INTEGER"))
    assert not cs.narrowed(col("INT"), col("BIGINT"))
    assert cs.narrowed(col("BIGINT"), col("INTEGER"))
    assert not cs.narrowed(col("INT"), col("INTEGER"))


def test_narrowed_char_varchar_interchange():
    assert not cs.narrowed(col("CHAR", 10), col("VARCHAR", 10))
    assert cs.narrowed(col("VARCHAR", 20), col("CHAR", 10))


def test_narrowed_unrelated_type_change_is_incompatible():
    assert cs.narrowed(col("DATE"), col("TIMESTAMP"))
    assert cs.narrowed(col("DECIMAL", 9, 2), col("INTEGER"))


# --- main (end-to-end against a git repo) -------------------------------------

def run(repo, capsys, new_ddl=None, extra=None, path="sql/ddl/create-tables.sql"):
    repo.write(path, BASE_DDL)
    repo.commit("base")
    repo.branch()
    if new_ddl is not None:
        repo.write(path, new_ddl)
    for p, text in (extra or {}).items():
        repo.write(p, text)
    repo.commit()
    rc = cs.main()
    return rc, capsys.readouterr().out


def test_main_unchanged_schema_passes(repo, capsys):
    rc, out = run(repo, capsys)
    assert rc == 0
    assert "== DB schema compatibility: PASS" in out


def test_main_additive_nullable_column_is_note(repo, capsys):
    ddl = BASE_DDL.replace("RENEWAL_COUNT   INTEGER,", "RENEWAL_COUNT   INTEGER,\n    API_FLAG CHAR(1),")
    rc, out = run(repo, capsys, ddl)
    assert rc == 0
    assert "NOTE      schema:ACMEINS.POLICIES.API_FLAG:column-added" in out


def test_main_new_not_null_column_with_default_is_note(repo, capsys):
    ddl = BASE_DDL.replace("RENEWAL_COUNT   INTEGER,", "RENEWAL_COUNT   INTEGER,\n    WEB_IND CHAR(1) NOT NULL WITH DEFAULT 'N',")
    rc, out = run(repo, capsys, ddl)
    assert rc == 0
    assert "schema:ACMEINS.POLICIES.WEB_IND:column-added" in out


def test_main_new_required_column_fails(repo, capsys):
    ddl = BASE_DDL.replace("RENEWAL_COUNT   INTEGER,", "RENEWAL_COUNT   INTEGER,\n    UW_STATUS CHAR(2) NOT NULL,")
    rc, out = run(repo, capsys, ddl)
    assert rc == 1
    assert "FAIL      schema:ACMEINS.POLICIES.UW_STATUS:new-required-column" in out


def test_main_dropped_column_fails(repo, capsys):
    ddl = BASE_DDL.replace("    AGENT_CODE      CHAR(6),\n", "")
    rc, out = run(repo, capsys, ddl)
    assert rc == 1
    assert "schema:ACMEINS.POLICIES.AGENT_CODE:column-dropped" in out


def test_main_dropped_table_fails(repo, capsys):
    start = BASE_DDL.index("CREATE TABLE ACMEINS.COVERAGES")
    end = BASE_DDL.index("CREATE SEQUENCE")
    rc, out = run(repo, capsys, BASE_DDL[:start] + BASE_DDL[end:])
    assert rc == 1
    assert "schema:ACMEINS.COVERAGES:table-dropped" in out


def test_main_narrowed_char_reports_type_narrowed(repo, capsys):
    rc, out = run(repo, capsys, BASE_DDL.replace("AGENT_CODE      CHAR(6)", "AGENT_CODE      CHAR(4)"))
    assert rc == 1
    assert "FAIL      schema:ACMEINS.POLICIES.AGENT_CODE:type-narrowed: CHAR(6) -> CHAR(4)" in out


def test_main_incompatible_type_reports_type_changed(repo, capsys):
    rc, out = run(repo, capsys, BASE_DDL.replace("TOTAL_PREMIUM   DECIMAL(11,2)", "TOTAL_PREMIUM   DATE"))
    assert rc == 1
    assert "schema:ACMEINS.POLICIES.TOTAL_PREMIUM:type-changed" in out


def test_main_widened_type_passes(repo, capsys):
    rc, _out = run(repo, capsys, BASE_DDL.replace("SMALLINT", "INTEGER").replace("DECIMAL(11,2)", "DECIMAL(13,2)"))
    assert rc == 0


def test_main_made_not_null_without_default_fails(repo, capsys):
    rc, out = run(repo, capsys, BASE_DDL.replace("AGENT_CODE      CHAR(6),", "AGENT_CODE      CHAR(6) NOT NULL,"))
    assert rc == 1
    assert "schema:ACMEINS.POLICIES.AGENT_CODE:made-not-null" in out


def test_main_removed_constraint_fails(repo, capsys):
    ddl = BASE_DDL.replace(",\n    CONSTRAINT PK_POLICIES PRIMARY KEY (POLICY_NUMBER)", "")
    rc, out = run(repo, capsys, ddl)
    assert rc == 1
    assert "schema:ACMEINS.POLICIES:PRIMARY KEY:PK_POLICIES:constraint-removed" in out


def test_main_sequence_changed_and_dropped(repo, capsys):
    rc, out = run(repo, capsys, BASE_DDL.replace("START WITH 1000000", "START WITH 1"))
    assert rc == 1
    assert "schema:ACMEINS.POLICY_SEQ:sequence-changed" in out


def test_main_sequence_dropped(repo, capsys):
    ddl = BASE_DDL[:BASE_DDL.index("CREATE SEQUENCE")]
    rc, out = run(repo, capsys, ddl)
    assert rc == 1
    assert "schema:ACMEINS.POLICY_SEQ:sequence-dropped" in out


def test_main_removed_schema_file_fails(repo, capsys):
    repo.write("sql/ddl/create-tables.sql", BASE_DDL)
    repo.commit("base")
    repo.branch()
    repo.remove("sql/ddl/create-tables.sql")
    repo.commit()
    assert cs.main() == 1
    assert "schema:sql/ddl/create-tables.sql:file-removed: sql/ddl/create-tables.sql removed (2 tables)" in capsys.readouterr().out


def test_main_checks_facade_schema_sql_but_ignores_other_sql(repo, capsys):
    rc, out = run(repo, capsys, BASE_DDL.replace("    AGENT_CODE      CHAR(6),\n", ""),
                  path="java-facade/src/main/resources/schema.sql")
    assert rc == 1
    assert "(java-facade/src/main/resources/schema.sql)" in out


def test_main_ignores_sql_outside_schema_locations(repo, capsys):
    rc, _out = run(repo, capsys, "", path="java-facade/src/main/resources/data.sql")
    assert rc == 0


def test_main_flyway_migration_modified(repo, capsys):
    mig = "svc/src/main/resources/db/migration/V1__init.sql"
    repo.write(mig, "CREATE TABLE A (X INT);")
    repo.commit("base")
    repo.branch()
    repo.write(mig, "CREATE TABLE A (X INT, Y INT);")
    repo.write("svc/src/main/resources/db/migration/V2__more.sql", "ALTER TABLE A ADD Z INT;")
    repo.commit()
    assert cs.main() == 1
    out = capsys.readouterr().out
    assert f"schema:{mig}:applied-migration-modified" in out
    assert "V2__more.sql" not in out


def test_main_allowlisted_drop_is_approved(repo, capsys):
    import json
    allowed = {"allowed": [{"id": "schema:ACMEINS.POLICIES.AGENT_CODE:column-dropped", "ticket": "AB-521", "reason": "moved"}]}
    rc, out = run(repo, capsys, BASE_DDL.replace("    AGENT_CODE      CHAR(6),\n", ""),
                  extra={"compat/allowed-changes.json": json.dumps(allowed)})
    assert rc == 0
    assert "APPROVED  schema:ACMEINS.POLICIES.AGENT_CODE:column-dropped" in out
