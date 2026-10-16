import os
from pathlib import Path
import re
import unittest


def parse_paragraphs(source):
    procedure_source = source.split("PROCEDURE DIVISION.", 1)[1]
    paragraphs = {}
    current_name = None
    current_lines = []

    for line in procedure_source.splitlines():
        if len(line) >= 7 and line[6] == "*":
            continue

        area_a_text = line[7:].strip() if len(line) > 7 else ""
        if re.fullmatch(r"[A-Z0-9][A-Z0-9-]*\.", area_a_text):
            if current_name is not None:
                paragraphs[current_name] = " ".join(current_lines)
            current_name = area_a_text[:-1]
            current_lines = []
        elif current_name is not None:
            current_lines.append(line[7:].strip())

    if current_name is not None:
        paragraphs[current_name] = " ".join(current_lines)

    return paragraphs


class UnderwritingAuthorizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source_path = os.environ.get("UNDWRT_SOURCE")
        if source_path is None:
            source_path = (
                Path(__file__).resolve().parents[2]
                / "cobol"
                / "programs"
                / "UNDWRT.cbl"
            )
        cls.paragraphs = parse_paragraphs(
            Path(source_path).read_text(encoding="utf-8")
        )

    def test_authorization_paragraph_checks_racf_update(self):
        matching_paragraphs = []

        for name, body in self.paragraphs.items():
            normalized = " ".join(body.upper().split())
            if (
                "EXEC CICS ASSIGN" in normalized
                and "USERID(" in normalized
                and "EXEC CICS QUERY SECURITY" in normalized
                and "UPDATE(" in normalized
                and "DFHVALUE(UPDATABLE)" in normalized
                and re.search(
                    r"MOVE 'NOT AUTHORIZED FOR UNDERWRITING DECISION' "
                    r"TO WS-ERROR-MSG",
                    normalized,
                )
            ):
                matching_paragraphs.append(name)

        self.assertTrue(
            matching_paragraphs,
            "No paragraph assigns the caller and checks RACF UPDATE access",
        )

    def test_authorization_gates_writes_and_mq(self):
        main_logic = " ".join(self.paragraphs["0000-MAIN-LOGIC"].upper().split())
        authorization_paragraphs = [
            name
            for name, body in self.paragraphs.items()
            if "EXEC CICS ASSIGN" in body.upper()
            and "EXEC CICS QUERY SECURITY" in body.upper()
        ]
        self.assertTrue(authorization_paragraphs, "Authorization paragraph missing")

        authorization_perform = re.search(
            rf"PERFORM\s+{re.escape(authorization_paragraphs[0])}\b",
            main_logic,
        )
        write_perform = re.search(r"PERFORM\s+6000-WRITE-DECISION\b", main_logic)
        mq_perform = re.search(r"PERFORM\s+7000-SEND-MQ-NOTIFICATION\b", main_logic)

        self.assertIsNotNone(authorization_perform)
        self.assertIsNotNone(write_perform)
        self.assertIsNotNone(mq_perform)
        self.assertLess(authorization_perform.start(), write_perform.start())
        self.assertLess(authorization_perform.start(), mq_perform.start())
        self.assertIn(
            "IF WS-ERROR-MSG = SPACES",
            main_logic[authorization_perform.end() : write_perform.start()],
        )

    def test_decision_records_caller_identity(self):
        decision_logic = " ".join(self.paragraphs["6000-WRITE-DECISION"].split())
        insert_statement = re.search(
            r"INSERT INTO UNDERWRITING_DECISIONS\b.*?END-EXEC",
            decision_logic,
            re.IGNORECASE,
        )

        self.assertIsNotNone(insert_statement)
        self.assertNotIn("'SYSTEM'", insert_statement.group(0).upper())
        self.assertIn(":WS-USERID", insert_statement.group(0).upper())

    def test_map_input_limited_to_policy_number(self):
        read_logic = " ".join(self.paragraphs["2000-READ-POLICY-DATA"].split())
        receive_position = read_logic.upper().find("EXEC CICS RECEIVE")
        receive_end = read_logic.upper().find("END-EXEC", receive_position)
        initialize_position = read_logic.upper().find("INITIALIZE POLICY-RECORD")
        select_position = read_logic.upper().find("EXEC SQL SELECT")

        self.assertGreaterEqual(receive_position, 0)
        self.assertGreater(receive_end, receive_position)
        self.assertGreater(initialize_position, receive_end)
        self.assertGreater(select_position, initialize_position)

        select_end = read_logic.upper().find("END-EXEC", select_position)
        select_statement = read_logic[select_position:select_end].upper()
        self.assertIn("POLICYHOLDER_ID", select_statement)
        self.assertIn("POLICY_STATUS", select_statement)


if __name__ == "__main__":
    unittest.main()
