import copy
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(
    os.environ.get("POLRNW_SOURCE", ROOT / "cobol/programs/POLRNW.cbl")
).resolve()
DEFAULT_FIXTURE = {
    "policy_number": "POL000000001",
    "policy_type": "AUT",
    "status": "AC",
    "premium": "1000.00",
    "expiry": "20261031",
    "renewal_count": "2",
    "uw_status": "AP",
    "agent": "AG1001",
    "branch": "CHI1",
    "user": "AGT1001",
    "open_claims": 0,
    "update_rows": 1,
    "select_sqlcode": 0,
    "grants": [],
    "map": {"agent": "ATTACK", "branch": "ATK1"},
}
SELECT_COLUMNS = {
    "POLICY_NUMBER": "policy_number",
    "POLICY_TYPE": "policy_type",
    "POLICY_STATUS": "status",
    "TOTAL_PREMIUM": "premium",
    "EXPIRY_DATE": "expiry",
    "RENEWAL_COUNT": "renewal_count",
    "UW_STATUS": "uw_status",
    "AGENT_CODE": "agent",
    "BRANCH_CODE": "branch",
}


def cobol_literal(value):
    if value is None or value == "":
        return "SPACES"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def split_sql_list(value):
    parts = []
    start = 0
    depth = 0
    quoted = False
    for index, char in enumerate(value):
        if char == "'":
            quoted = not quoted
        elif not quoted and char == "(":
            depth += 1
        elif not quoted and char == ")":
            depth -= 1
        elif not quoted and depth == 0 and char == ",":
            parts.append(value[start:index].strip())
            start = index + 1
    parts.append(value[start:].strip())
    return parts


def source_line(statement):
    line = "           " + statement
    if len(line) > 72:
        raise ValueError(f"Generated COBOL line exceeds column 72: {line}")
    return line


def generated_block(statements, trailing_period=False):
    lines = [source_line(statement) for statement in statements]
    if trailing_period:
        if len(lines[-1]) == 72:
            lines.append(source_line("."))
        else:
            lines[-1] += "."
    return "\n".join(lines)


def replace_exec_block(match, fixture):
    kind = match.group(1).upper()
    block = match.group(2)
    trailing_period = bool(match.group(3))
    normalized = " ".join(block.split())
    statements = []

    if kind == "CICS" and re.search(r"\bASSIGN\b", normalized, re.I):
        user = re.search(r"\bUSERID\s*\(\s*([^)]+)\)", normalized, re.I)
        response = re.search(r"\bRESP\s*\(\s*([^)]+)\)", normalized, re.I)
        if not user or not response:
            raise ValueError(f"Unrecognized CICS ASSIGN block: {normalized}")
        statements = [
            f"MOVE {cobol_literal(fixture['user'])} TO {user.group(1).strip()}",
            f"MOVE 0 TO {response.group(1).strip()}",
        ]
    elif kind == "CICS" and re.search(r"\bASKTIME\b", normalized, re.I):
        target = re.search(r"\bABSTIME\s*\(\s*([^)]+)\)", normalized, re.I)
        if not target:
            raise ValueError(f"Unrecognized CICS ASKTIME block: {normalized}")
        statements = [f"MOVE 20261016 TO {target.group(1).strip()}"]
    elif kind == "CICS" and re.search(r"\bFORMATTIME\b", normalized, re.I):
        statements = ["CONTINUE"]
    elif kind == "CICS" and re.search(r"\bRECEIVE\b", normalized, re.I):
        record = re.search(r"\bINTO\s*\(\s*([^)]+)\)", normalized, re.I)
        response = re.search(r"\bRESP\s*\(\s*([^)]+)\)", normalized, re.I)
        if not record or not response:
            raise ValueError(f"Unrecognized CICS RECEIVE block: {normalized}")
        map_values = fixture["map"]
        statements = [
            f"MOVE SPACES TO {record.group(1).strip()}",
            f"MOVE {cobol_literal(fixture['policy_number'])} TO POLICY-NUMBER",
            f"MOVE {cobol_literal(map_values.get('agent'))} TO POLICY-AGENT-CODE",
            f"MOVE {cobol_literal(map_values.get('branch'))} TO POLICY-BRANCH-CODE",
            f"MOVE 0 TO {response.group(1).strip()}",
        ]
    elif kind == "CICS" and re.search(r"\bQUERY\s+SECURITY\b", normalized, re.I):
        resid = re.search(r"\bRESID\s*\(\s*([^)]+)\)", normalized, re.I)
        length = re.search(r"\bRESIDLENGTH\s*\(\s*([^)]+)\)", normalized, re.I)
        update = re.search(r"\bUPDATE\s*\(\s*([^)]+)\)", normalized, re.I)
        response = re.search(r"\bRESP\s*\(\s*([^)]+)\)", normalized, re.I)
        if not all((resid, length, update, response)):
            raise ValueError(f"Unrecognized CICS QUERY SECURITY block: {normalized}")
        resid_var = resid.group(1).strip()
        length_var = length.group(1).strip()
        update_var = update.group(1).strip()
        statements = [f"DISPLAY 'QSEC ' {resid_var}(1:{length_var})"]
        if fixture["grants"]:
            statements.append(f"EVALUATE {resid_var}(1:{length_var})")
            for grant in fixture["grants"]:
                statements.extend(
                    [
                        f"WHEN {cobol_literal(grant)}",
                        f"MOVE 1 TO {update_var}",
                    ]
                )
            statements.extend(
                [
                    "WHEN OTHER",
                    f"MOVE 2 TO {update_var}",
                    "END-EVALUATE",
                ]
            )
        else:
            statements.append(f"MOVE 2 TO {update_var}")
        statements.append(f"MOVE 0 TO {response.group(1).strip()}")
    elif kind == "SQL" and re.search(r"\bSELECT\s+COUNT\s*\(\s*\*\s*\)", normalized, re.I):
        host = re.search(r"\bINTO\s*:\s*([A-Z0-9-]+)", normalized, re.I)
        if not host:
            raise ValueError(f"Unrecognized SQL COUNT block: {normalized}")
        statements = [
            f"MOVE {fixture['open_claims']} TO {host.group(1)}",
            "MOVE 0 TO SQLCODE",
        ]
    elif kind == "SQL" and re.search(r"\bSELECT\b", normalized, re.I):
        select = re.search(
            r"\bSELECT\s+(.*?)\s+INTO\s+(.*?)\s+FROM\s+POLICIES\b",
            block,
            re.I | re.S,
        )
        if not select:
            raise ValueError(f"Unrecognized SQL SELECT block: {normalized}")
        columns = split_sql_list(select.group(1))
        hosts = re.findall(r":\s*([A-Z0-9-]+)", select.group(2))
        if len(columns) != len(hosts):
            raise ValueError(
                f"SQL SELECT has {len(columns)} columns but {len(hosts)} host variables"
            )
        for column, host in zip(columns, hosts):
            column_name = column.upper()
            coalesce = re.fullmatch(
                r"COALESCE\s*\(\s*([A-Z0-9_]+)\s*,.*\)",
                column_name,
                re.I,
            )
            if coalesce:
                column_name = coalesce.group(1).upper()
            if column_name not in SELECT_COLUMNS:
                raise ValueError(f"Unrecognized POLICIES SELECT column: {column}")
            value = fixture[SELECT_COLUMNS[column_name]]
            statements.append(f"MOVE {cobol_literal(value)} TO {host}")
        statements.append(f"MOVE {fixture['select_sqlcode']} TO SQLCODE")
    elif kind == "SQL" and re.search(r"\bUPDATE\s+POLICIES\b", normalized, re.I):
        statements = [
            "DISPLAY 'SQL UPDATE POLICIES '",
            "POLICY-UW-STATUS ' ' POLICY-RENEWAL-COUNT",
            "MOVE 0 TO SQLCODE",
            f"MOVE {fixture['update_rows']} TO SQLERRD(3)",
        ]
    elif kind == "SQL" and re.search(r"\bUPDATE\s+COVERAGES\b", normalized, re.I):
        statements = [
            "DISPLAY 'SQL UPDATE COVERAGES'",
            "MOVE 0 TO SQLCODE",
        ]
    elif kind == "CICS" and re.search(r"\bSEND\s+TEXT\b", normalized, re.I):
        source = re.search(r"\bFROM\s*\(\s*([^)]+)\)", normalized, re.I)
        if not source:
            raise ValueError(f"Unrecognized CICS SEND TEXT block: {normalized}")
        statements = [f"DISPLAY 'SEND ERROR ' {source.group(1).strip()}"]
    elif kind == "CICS" and re.search(r"\bSEND\b", normalized, re.I):
        statements = ["DISPLAY 'SEND CONFIRMATION'"]
    elif kind == "CICS" and re.search(r"\bRETURN\b", normalized, re.I):
        statements = ["GOBACK"]
    else:
        raise ValueError(f"Unrecognized EXEC {kind} block: {normalized}")

    return generated_block(statements, trailing_period)


def transformed_source(fixture):
    source = SOURCE.read_text()

    def expand_copy(match):
        copybook = ROOT / "cobol/copybooks" / f"{match.group(1)}.cpy"
        if not copybook.is_file():
            raise ValueError(f"Missing copybook: {copybook}")
        return copybook.read_text()

    source = re.sub(
        r"(?im)^\s*COPY\s+([A-Z0-9-]+)\.\s*$",
        expand_copy,
        source,
    )
    source = re.sub(
        r"EXEC\s+SQL\s+INCLUDE\s+SQLCA\s+END-EXEC\.",
        "\n".join(
            [
                "       01 SQLCA.",
                "          05 SQLCODE PIC S9(9) COMP-5.",
                "          05 SQLERRD PIC S9(9) COMP-5 OCCURS 6.",
            ]
        ),
        source,
        flags=re.I,
    )
    source = re.sub(
        r"(?im)^       LINKAGE SECTION\.\s*\n(       01\s+DFHCOMMAREA[^\n]*\n)",
        r"\1",
        source,
    )
    source = source.replace("DFHRESP(NORMAL)", "0")
    source = source.replace("DFHVALUE(UPDATABLE)", "1")
    source = re.sub(
        r"EXEC\s+(CICS|SQL)\b(.*?)END-EXEC(\.)?",
        lambda match: replace_exec_block(match, fixture),
        source,
        flags=re.I | re.S,
    )
    return source


def make_fixture(**overrides):
    fixture = copy.deepcopy(DEFAULT_FIXTURE)
    for key, value in overrides.items():
        if key == "map":
            fixture["map"].update(value)
        else:
            fixture[key] = value
    return fixture


class PolrnwHarnessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("cobc") is None:
            raise RuntimeError(
                "GnuCOBOL compiler 'cobc' is required; install it with "
                "'brew install gnucobol' (macOS) or your platform package manager."
            )
        if not SOURCE.is_file():
            raise RuntimeError(f"POLRNW source file does not exist: {SOURCE}")

    def run_program(self, fixture=None):
        fixture = fixture or make_fixture()
        with tempfile.TemporaryDirectory(prefix="polrnw-test-") as temp_dir:
            temp_path = Path(temp_dir)
            cobol_path = temp_path / "POLRNW.cbl"
            executable = temp_path / "polrnw"
            cobol_path.write_text(transformed_source(fixture))
            compile_result = subprocess.run(
                ["cobc", "-x", "-o", str(executable), str(cobol_path)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(
                compile_result.returncode,
                0,
                f"cobc failed:\n{compile_result.stdout}\n{compile_result.stderr}",
            )
            run_result = subprocess.run(
                [str(executable)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(
                run_result.returncode,
                0,
                f"POLRNW failed:\n{run_result.stdout}\n{run_result.stderr}",
            )
            return run_result.stdout

    def assert_renewed(self, output):
        self.assertIn("SQL UPDATE POLICIES", output)
        self.assertIn("SQL UPDATE COVERAGES", output)
        self.assertIn("SEND CONFIRMATION", output)
        self.assertNotIn("SEND ERROR", output)

    def assert_denied(self, output):
        self.assertNotIn("SQL UPDATE", output)
        self.assertIn("SEND ERROR POLICY NOT FOUND FOR RENEWAL", output)
        self.assertNotIn("SEND CONFIRMATION", output)

    def test_agent_grant_renews(self):
        self.assert_renewed(self.run_program(make_fixture(grants=["PAS.AGENT.AG1001"])))

    def test_branch_grant_renews(self):
        self.assert_renewed(self.run_program(make_fixture(grants=["PAS.BRANCH.CHI1"])))

    def test_no_grants_denied(self):
        self.assert_denied(self.run_program())

    def test_map_agent_does_not_override_database_authority(self):
        output = self.run_program(
            make_fixture(
                grants=["PAS.AGENT.ATTACK"],
                map={"agent": "ATTACK"},
            )
        )
        self.assert_denied(output)

    def test_more_than_three_open_claims_blocks_renewal(self):
        output = self.run_program(
            make_fixture(grants=["PAS.AGENT.AG1001"], open_claims=4)
        )
        self.assertNotIn("SQL UPDATE", output)
        self.assertIn("SEND ERROR RENEWAL BLOCKED - TOO MANY OPEN CLAIMS", output)
        self.assertNotIn("SEND CONFIRMATION", output)

    def test_three_open_claims_allows_renewal(self):
        output = self.run_program(
            make_fixture(grants=["PAS.AGENT.AG1001"], open_claims=3)
        )
        self.assert_renewed(output)

    def test_blank_signed_on_user_fails_closed(self):
        output = self.run_program(make_fixture(user=""))
        self.assertNotIn("SQL UPDATE", output)
        self.assertIn("SEND ERROR UNABLE TO DETERMINE SIGNED-ON USER", output)
        self.assertNotIn("SEND CONFIRMATION", output)

    def test_unassigned_policy_grant_renews(self):
        output = self.run_program(
            make_fixture(agent=None, branch=None, grants=["PAS.UNASSIGNED"])
        )
        self.assert_renewed(output)

    def test_unassigned_policy_without_grant_is_denied(self):
        output = self.run_program(make_fixture(agent=None, branch=None))
        self.assertIn("QSEC PAS.UNASSIGNED", output)
        self.assert_denied(output)

    def test_zero_updated_policy_rows_blocks_followup_updates(self):
        output = self.run_program(
            make_fixture(grants=["PAS.AGENT.AG1001"], update_rows=0)
        )
        self.assertIn("SQL UPDATE POLICIES", output)
        self.assertNotIn("SQL UPDATE COVERAGES", output)
        self.assertNotIn("SEND CONFIRMATION", output)
        self.assertIn("SEND ERROR RENEWAL NOT APPLIED - POLICY CHANGED", output)

    def test_missing_policy_skips_authorization(self):
        output = self.run_program(
            make_fixture(select_sqlcode=100, grants=["PAS.AGENT.AG1001"])
        )
        self.assertNotIn("QSEC ", output)
        self.assertNotIn("SQL UPDATE", output)
        self.assertIn("SEND ERROR POLICY NOT FOUND FOR RENEWAL", output)


if __name__ == "__main__":
    unittest.main()
