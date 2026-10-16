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
    """Parse WS items: {name: {'level': n, 'parent': name|None, 'values': set}}.

    88-level entries record their VALUE set under their own name and are
    linked to the field they condition.
    """
    items = {}
    order = []  # (level, name) stack for parent resolution
    in_ws = False
    pending_88 = None
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
        m = re.match(r"^(01|05|88)\s+([A-Z0-9-]+)\.?(.*)$", s)
        if m:
            level, name, rest = int(m.group(1)), m.group(2), m.group(3)
            if level == 88:
                parent = order[-1][1] if order else None
                items[name] = {"level": 88, "parent": parent,
                               "values": set()}
                pending_88 = name
            else:
                while order and order[-1][0] >= level:
                    order.pop()
                parent = order[-1][1] if order else None
                items[name] = {"level": level, "parent": parent,
                               "values": set()}
                order.append((level, name))
                pending_88 = None
            rest_target = name if level == 88 else None
            if rest_target:
                items[name]["values"] |= set(re.findall(r"'([^']*)'", rest))
        elif pending_88 is not None:
            # continuation line of an 88 VALUE clause
            items[pending_88]["values"] |= set(re.findall(r"'([^']*)'", s))
            if s.endswith("."):
                pending_88 = None
    return items


def _children_of(items, group):
    return {n for n, d in items.items() if d["parent"] == group}


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
        hvs = [h.strip().lstrip(":") for h in select.group(2).split(",")]
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
        m = re.search(
            r"IF\s+WS-ERROR-MSG\s*=\s*SPACES(.*?)(?:ELSE|END-IF)",
            p0000, re.S)
        gated = [g for g in re.finditer(
            r"IF\s+WS-ERROR-MSG\s*=\s*SPACES(.*?)END-IF", p0000, re.S)]
        apply_gated = any("PERFORM 5000-APPLY-ENDORSEMENT" in g.group(1)
                          for g in gated)
        self.assertTrue(apply_gated,
                        "5000-APPLY-ENDORSEMENT is not gated on "
                        "WS-ERROR-MSG = SPACES in 0000-MAIN-LOGIC")


if __name__ == "__main__":
    unittest.main()
