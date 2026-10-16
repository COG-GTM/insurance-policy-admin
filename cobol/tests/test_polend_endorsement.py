"""Regression tests for POLEND.cbl endorsement security controls.

Parses fixed-format COBOL: columns 8-72 hold code, column 7 '*' marks a
comment line. WORKING-STORAGE 01/05/88 items and PROCEDURE DIVISION
paragraphs are extracted and asserted on.
"""
import re
import unittest
from pathlib import Path

POLEND = Path(__file__).resolve().parents[1] / "programs" / "POLEND.cbl"


def _code_lines(path):
    """Return (line text cols 8-72) for non-comment lines."""
    out = []
    for raw in path.read_text().splitlines():
        raw = raw.rstrip("\n")
        if len(raw) >= 7 and raw[6] == "*":
            continue
        out.append(raw[7:72] if len(raw) > 7 else "")
    return out


def _split_paragraphs(lines):
    """Split PROCEDURE DIVISION code lines into {name: text} paragraphs.

    A paragraph header is a line whose first token ends with '.' at col 8+
    (e.g. '2000-RECEIVE-ENDORSEMENT.').
    """
    paras = {}
    current = None
    in_proc = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("PROCEDURE DIVISION"):
            in_proc = True
            continue
        if not in_proc or not stripped:
            continue
        m = re.match(r"^([A-Z0-9][A-Z0-9-]*)\.\s*$", stripped)
        if m:
            current = m.group(1)
            paras[current] = ""
        elif current is not None:
            paras[current] += stripped + "\n"
    return paras


def _parse_working_storage(lines):
    """Parse WS items keyed by unique name (FILLERs become FILLER#n).

    Each entry: {'level', 'name', 'parent', 'pic', 'usage', 'values',
    'children'} where 'children' is the ordered list of direct-child keys
    and 'pic'/'usage' hold the PIC clause and usage (COMP/COMP-3/None).
    """
    items = {}
    stack = []  # (level, key) for parent resolution
    in_ws = False
    pending_88 = None
    filler_n = 0
    for line in lines:
        s = line.strip()
        if s.startswith("WORKING-STORAGE SECTION"):
            in_ws = True
            continue
        if in_ws and (s.startswith("LINKAGE SECTION")
                      or s.startswith("PROCEDURE DIVISION")):
            break
        if not in_ws or not s:
            continue
        m = re.match(r"^(\d\d)\s+([A-Z0-9-]+)\.?(.*)$", s)
        if m:
            level, name, rest = int(m.group(1)), m.group(2), m.group(3)
            if level == 88:
                parent = stack[-1][1] if stack else None
                items[name] = {"level": 88, "name": name,
                               "parent": parent, "pic": None,
                               "usage": None, "values": set(),
                               "children": []}
                if parent:
                    items[parent]["children"].append(name)
                pending_88 = name
            elif 1 <= level <= 49:
                while stack and stack[-1][0] >= level:
                    stack.pop()
                parent = stack[-1][1] if stack else None
                key = name
                if name == "FILLER":
                    filler_n += 1
                    key = f"FILLER#{filler_n}"
                pic = re.search(r"PIC(TURE)?\s+(\S+)", rest)
                items[key] = {"level": level, "name": name,
                              "parent": parent,
                              "pic": pic.group(2).rstrip(".")
                                   if pic else None,
                              "usage": ("COMP-3" if "COMP-3" in rest
                                        else "COMP" if re.search(
                                            r"\bCOMP\b", rest) else None),
                              "values": set(), "children": []}
                if parent:
                    items[parent]["children"].append(key)
                stack.append((level, key))
                pending_88 = None
            else:
                continue
            if level == 88:
                items[name]["values"] |= set(re.findall(r"'([^']*)'",
                                                       rest))
        elif pending_88 is not None:
            # continuation line of an 88 VALUE clause
            items[pending_88]["values"] |= set(re.findall(r"'([^']*)'", s))
            if s.endswith("."):
                pending_88 = None
    return items


def _children_of(items, group):
    return list(items.get(group, {}).get("children", []))


def _receive_group(src):
    m = re.search(r"MAP\('POLEMAP'\).*?INTO\(([^)]+)\)", src, re.S)
    return m.group(1) if m else None


class PolEndTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lines = _code_lines(POLEND)
        cls.paras = _split_paragraphs(cls.lines)
        cls.ws = _parse_working_storage(cls.lines)
        cls.src = "\n".join(cls.lines)


class TestPolendEndorsement(PolEndTestBase):

    def test_endorsement_type_comes_from_received_map(self):
        # RECEIVE MAP('POLEMAP') INTO(X): X must be a WS group defined here
        m = re.search(r"RECEIVE\s+.*?MAP\('POLEMAP'\).*?INTO\(([^)]+)\)",
                      self.src, re.S)
        self.assertIsNotNone(m, "no RECEIVE MAP('POLEMAP') ... INTO found")
        target = m.group(1)
        self.assertIn(target, self.ws,
                      f"RECEIVE INTO target {target} is not defined in WS")
        self.assertTrue(_children_of(self.ws, target),
                        f"{target} has no child fields in POLEND.cbl")
        # paragraph 2000 moves a child of X into WS-ENDORSEMENT-TYPE
        p2000 = self.paras.get("2000-RECEIVE-ENDORSEMENT", "")
        moves = re.findall(r"MOVE\s+([A-Z0-9-]+)\s+TO\s+WS-ENDORSEMENT-TYPE",
                           p2000)
        self.assertTrue(moves, "2000 never populates WS-ENDORSEMENT-TYPE")
        children = _children_of(self.ws, target)
        self.assertTrue(any(mv in children for mv in moves),
                        f"{moves} are not children of {target}")

    def test_validation_rejects_unknown_endorsement_types(self):
        p3000 = self.paras.get("3000-VALIDATE-ENDORSEMENT", "")
        conds = re.findall(r"IF\s+NOT\s+([A-Z0-9-]+)", p3000)
        type_conds = [c for c in conds
                      if self.ws.get(c, {}).get("parent")
                      == "WS-ENDORSEMENT-TYPE"]
        self.assertTrue(type_conds,
                        "3000 has no IF NOT <88-on-WS-ENDORSEMENT-TYPE>")
        values = set()
        for c in type_conds:
            values |= self.ws[c]["values"]
        self.assertEqual(values, {"CAD", "CRM", "LCH", "ACH", "CAN"})
        for bad in ("\x00" * 3, "   ", "XYZ", "can", "CA "):
            self.assertNotIn(bad, values)
        for good in ("CAD", "CRM", "LCH", "ACH", "CAN"):
            self.assertIn(good, values)

    def test_cancellation_requires_confirmation(self):
        p3000 = self.paras.get("3000-VALIDATE-ENDORSEMENT", "")
        m = re.search(r"IF\s+END-TYPE-CANCEL\s+AND\s+NOT\s+([A-Z0-9-]+)",
                      p3000)
        self.assertIsNotNone(m,
                             "3000 lacks 'IF END-TYPE-CANCEL AND NOT <88>'")
        cond = m.group(1)
        parent = self.ws.get(cond, {}).get("parent")
        self.assertIsNotNone(parent, f"{cond} is not an 88-level condition")
        # the conditioned field must be a child of the RECEIVE INTO group
        recv = re.search(r"MAP\('POLEMAP'\).*?INTO\(([^)]+)\)",
                         self.src, re.S)
        self.assertIsNotNone(recv)
        recv_group = recv.group(1)
        self.assertIn(parent, _children_of(self.ws, recv_group),
                      f"{parent} is not a child of {recv_group}")

    def test_policy_authorization_checked_before_apply(self):
        # SELECT in 2000 fetches AGENT_CODE into :POLICY-AGENT-CODE
        p2000 = self.paras.get("2000-RECEIVE-ENDORSEMENT", "")
        select = re.search(r"SELECT\s+(.*?)\s+INTO\s+(.*?)\s+FROM", p2000,
                           re.S)
        self.assertIsNotNone(select, "no SELECT in paragraph 2000")
        cols = [c.strip() for c in select.group(1).split(",")]
        hvs = [h.strip().split()[0].lstrip(":")
               for h in select.group(2).split(",")]
        self.assertIn("AGENT_CODE", cols)
        self.assertEqual(cols.index("AGENT_CODE"),
                         hvs.index("POLICY-AGENT-CODE"),
                         "AGENT_CODE not aligned with :POLICY-AGENT-CODE")
        # a paragraph performed from 3000 runs EXEC CICS QUERY SECURITY
        p3000 = self.paras.get("3000-VALIDATE-ENDORSEMENT", "")
        performed = set(re.findall(r"PERFORM\s+([A-Z0-9-]+)", p3000))
        checked = False
        for name in performed:
            body = self.paras.get(name, "")
            if ("EXEC CICS QUERY SECURITY" in body
                    and "RESID(POLICY-AGENT-CODE)" in body
                    and "UPDATE(" in body
                    and "DFHVALUE(UPDATABLE)" in body
                    and re.search(r"MOVE\s+'[^']+'\s+TO\s+WS-ERROR-MSG",
                                  body)):
                checked = True
        self.assertTrue(checked,
                        "no performed paragraph does QUERY SECURITY on "
                        "POLICY-AGENT-CODE with UPDATE/DFHVALUE(UPDATABLE)")
        # 0000-MAIN-LOGIC gates 5000-APPLY-ENDORSEMENT on WS-ERROR-MSG
        p0000 = self.paras.get("0000-MAIN-LOGIC", "")
        gated = [g for g in re.finditer(
            r"IF\s+WS-ERROR-MSG\s*=\s*SPACES(.*?)END-IF", p0000, re.S)]
        apply_gated = any("PERFORM 5000-APPLY-ENDORSEMENT" in g.group(1)
                          for g in gated)
        self.assertTrue(apply_gated,
                        "5000-APPLY-ENDORSEMENT is not gated on "
                        "WS-ERROR-MSG = SPACES in 0000-MAIN-LOGIC")

    def test_receive_area_is_bms_symbolic_map(self):
        # RECEIVE INTO target must be a BMS symbolic input map:
        # first child a 12-byte TIOA-prefix FILLER, then per field
        # <stem>L (S9(04) COMP), <stem>F (X(01)), <stem>I (data).
        group = _receive_group(self.src)
        self.assertIn(group, self.ws,
                      "RECEIVE INTO target is not a WS group defined here")
        children = _children_of(self.ws, group)
        self.assertTrue(children, f"{group} has no children")
        first = self.ws[children[0]]
        self.assertEqual(first["name"], "FILLER",
                         "first child must be the TIOA prefix FILLER")
        self.assertEqual(first["pic"], "X(12)",
                         "TIOA prefix FILLER must be PIC X(12)")

        # fields 2000 moves out of the group + the field the
        # cancel-confirm 88 conditions
        p2000 = self.paras.get("2000-RECEIVE-ENDORSEMENT", "")
        moved = [m for m in re.findall(r"MOVE\s+([A-Z0-9-]+)\s+TO\s+",
                                       p2000)
                 if m in children]
        self.assertTrue(moved,
                        "2000 moves no fields out of the receive group")
        confirm_field = self.ws.get("IN-CANCEL-CONFIRMED", {}).get(
            "parent")
        targets = moved + ([confirm_field] if confirm_field else [])
        for t in targets:
            self.assertTrue(t.endswith("I"),
                            f"{t} is not a BMS input (I-suffix) field")
            idx = children.index(t)
            stem = t[:-1]
            l_item = self.ws[children[idx - 2]]
            f_item = self.ws[children[idx - 1]]
            self.assertEqual(l_item["name"], stem + "L",
                             f"{t} not preceded by {stem}L")
            self.assertEqual(l_item["pic"], "S9(04)",
                             f"{stem}L must be PIC S9(04)")
            self.assertEqual(l_item["usage"], "COMP",
                             f"{stem}L must be COMP")
            self.assertEqual(f_item["name"], stem + "F",
                             f"{t} not preceded by {stem}F")
            self.assertEqual(f_item["pic"], "X(01)",
                             f"{stem}F must be PIC X(01)")

    def test_policy_record_fully_loaded_from_db2(self):
        expected = [
            ("POLICY_NUMBER", "POLICY-NUMBER"),
            ("POLICY_TYPE", "POLICY-TYPE"),
            ("POLICY_STATUS", "POLICY-STATUS"),
            ("EFFECTIVE_DATE", "POLICY-EFFECTIVE-DATE"),
            ("EXPIRY_DATE", "POLICY-EXPIRY-DATE"),
            ("POLICYHOLDER_ID", "POLICY-HOLDER-ID"),
            ("AGENT_CODE", "POLICY-AGENT-CODE"),
            ("BRANCH_CODE", "POLICY-BRANCH-CODE"),
            ("TOTAL_PREMIUM", "POLICY-TOTAL-PREMIUM"),
            ("DEDUCTIBLE", "POLICY-DEDUCTIBLE"),
            ("COVERAGE_LIMIT", "POLICY-LIMIT"),
            ("INCEPTION_DATE", "POLICY-INCEPTION-DATE"),
            ("RENEWAL_COUNT", "POLICY-RENEWAL-COUNT"),
            ("UW_STATUS", "POLICY-UW-STATUS"),
            ("RISK_SCORE", "POLICY-RISK-SCORE"),
            ("WEB_INDICATOR", "POLICY-WEB-IND"),
            ("API_FLAG", "POLICY-API-FLAG"),
        ]
        p2000 = self.paras.get("2000-RECEIVE-ENDORSEMENT", "")
        select = re.search(r"SELECT\s+(.*?)\s+INTO\s+(.*?)\s+FROM", p2000,
                           re.S)
        self.assertIsNotNone(select, "no SELECT in paragraph 2000")
        cols = [c.strip() for c in select.group(1).split(",")]
        # each INTO entry may carry a null indicator: ":HV :IND"
        hvs = [h.strip().split()[0].lstrip(":")
               for h in select.group(2).split(",")]
        self.assertEqual(cols, [c for c, _ in expected],
                         "SELECT column list != POLQRY's full-record list")
        self.assertEqual(hvs, [h for _, h in expected],
                         "host variables not aligned with columns")

    def test_nullable_policy_columns_have_indicators(self):
        # nullable POLICIES columns per DDL must have :HV :IND in INTO
        ddl = (Path(__file__).resolve().parents[2]
               / "sql" / "ddl" / "create-tables.sql").read_text()
        m = re.search(r"CREATE TABLE ACMEINS\.POLICIES\s*\((.*?)\)\s+IN\b",
                      ddl, re.S)
        self.assertIsNotNone(m, "POLICIES CREATE TABLE not found in DDL")
        nullable = set()
        for line in m.group(1).splitlines():
            cm = re.match(r"\s*([A-Z_]+)\s+([A-Z0-9(),]+)(.*)$", line)
            if cm and cm.group(1) != "CONSTRAINT":
                if "NOT NULL" not in cm.group(3):
                    nullable.add(cm.group(1))

        p2000 = self.paras.get("2000-RECEIVE-ENDORSEMENT", "")
        select = re.search(r"SELECT\s+(.*?)\s+INTO\s+(.*?)\s+FROM", p2000,
                           re.S)
        self.assertIsNotNone(select)
        cols = [c.strip() for c in select.group(1).split(",")]
        entries = [e.strip() for e in select.group(2).split(",")]
        self.assertEqual(len(cols), len(entries))
        for col, entry in zip(cols, entries):
            if col not in nullable:
                continue
            tokens = re.findall(r":([A-Z0-9-]+)", entry)
            self.assertGreaterEqual(
                len(tokens), 2,
                f"nullable column {col} has no null indicator in INTO")
            ind = tokens[1]
            item = self.ws.get(ind, {})
            self.assertEqual(item.get("pic"), "S9(04)",
                             f"indicator {ind} for {col} not PIC S9(04)")
            self.assertEqual(item.get("usage"), "COMP",
                             f"indicator {ind} for {col} not COMP")

    def test_null_premium_rejected_before_apply(self):
        # a NULL TOTAL_PREMIUM must fail closed, not flow the
        # initialized zero through to 5000-APPLY-ENDORSEMENT
        p2000 = self.paras.get("2000-RECEIVE-ENDORSEMENT", "")
        m = re.search(
            r"IF\s+WS-PREM-IND\s*<\s*0(.*?END-IF)", p2000, re.S)
        self.assertIsNotNone(m,
                             "2000 does not test WS-PREM-IND < 0")
        self.assertRegex(
            m.group(1), r"MOVE\s+'[^']+'\s+TO\s+WS-ERROR-MSG",
            "WS-PREM-IND < 0 branch does not set WS-ERROR-MSG")


if __name__ == "__main__":
    unittest.main()
