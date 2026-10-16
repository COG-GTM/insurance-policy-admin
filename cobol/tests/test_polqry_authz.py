#!/usr/bin/env python3
"""Regression tests for PQRY (POLQRY) per-policy authorization.

POLQRY is compiled with GnuCOBOL after each EXEC CICS / EXEC SQL block is
replaced by a stub driven by T_* environment variables, then run from
0000-MAIN-LOGIC. Stubs print EVENT lines so tests can assert what was sent.
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
COBOL = os.path.dirname(HERE)
SOURCE = os.path.join(COBOL, "programs", "POLQRY.cbl")
COPYBOOKS = os.path.join(COBOL, "copybooks")
IND = " " * 11

CVDA = {"DFHRESP(NORMAL)": "0", "DFHVALUE(READABLE)": "35",
        "DFHVALUE(NOTREADABLE)": "36"}

HARNESS_WS = [
    "       01  SQLCODE                PIC S9(9) COMP-5 VALUE 0.",
    "       01  T-FLAG                 PIC X(01).",
    "       01  T-GRANT                PIC X(64).",
]


def opt(block, name):
    m = re.search(name + r"\(([^)]*)\)", block)
    return m.group(1).strip() if m else None


def stub(block):
    b = " ".join(block.split())
    resp = opt(b, "RESP")
    set_resp = [f"MOVE 0 TO {resp}"] if resp else []
    if b.startswith("SQL INCLUDE SQLCA"):
        return None
    if b.startswith("CICS RECEIVE"):
        return ["ACCEPT POLICY-NUMBER FROM ENVIRONMENT 'T_POLNUM'"] + set_resp
    if b.startswith("CICS ASSIGN"):
        out = []
        for o, env in (("STARTCODE", "T_STARTCODE"), ("BRIDGE", "T_BRIDGE"),
                       ("USERID", "T_USERID")):
            if opt(b, o):
                out.append(f"ACCEPT {opt(b, o)} FROM ENVIRONMENT '{env}'")
        return out + set_resp
    if b.startswith("CICS QUERY SECURITY"):
        resid, read = opt(b, "RESID"), opt(b, "READ")
        return [f"DISPLAY 'EVENT QSEC ' {resid}",
                "ACCEPT T-GRANT FROM ENVIRONMENT 'T_GRANT'",
                f"IF {resid} = T-GRANT",
                f"    MOVE 35 TO {read}",
                "ELSE",
                f"    MOVE 36 TO {read}",
                "END-IF"] + set_resp
    if b.startswith("SQL SELECT") and "FROM POLICIES " in b:
        return ["ACCEPT T-FLAG FROM ENVIRONMENT 'T_FOUND'",
                "IF T-FLAG = 'Y'",
                "    MOVE 0 TO SQLCODE",
                "    ACCEPT POLICY-AGENT-CODE FROM ENVIRONMENT 'T_AGENT'",
                "    ACCEPT POLICY-BRANCH-CODE FROM ENVIRONMENT 'T_BRANCH'",
                "    ACCEPT POLICY-API-FLAG FROM ENVIRONMENT 'T_APIFLAG'",
                "ELSE",
                "    MOVE 100 TO SQLCODE",
                "END-IF"]
    if b.startswith("SQL SELECT") and "FROM POLICY_HOLDERS " in b:
        return ["DISPLAY 'EVENT READ-CUSTOMER'", "MOVE 0 TO SQLCODE"]
    if re.match(r"SQL (DECLARE|OPEN|CLOSE) ", b):
        return ["MOVE 0 TO SQLCODE"]
    if b.startswith("SQL FETCH"):
        return ["MOVE 100 TO SQLCODE"]
    if b.startswith("CICS SEND MAP"):
        return ["DISPLAY 'EVENT SEND-MAP ' POLICY-NUMBER"]
    if b.startswith("CICS SEND TEXT"):
        return ["DISPLAY 'EVENT SEND-TEXT ' WS-ERROR-MSG"]
    if b.startswith("CICS RETURN"):
        return ["STOP RUN"]
    raise AssertionError("no test stub for EXEC block: " + b)


def preprocess(src):
    out, lines, i = [], src.splitlines(), 0
    while i < len(lines):
        line = lines[i]
        if "EXEC " not in line[6:72]:
            out.append(line)
            i += 1
            continue
        buf = [line[line.index("EXEC ") + 5:]]
        while "END-EXEC" not in buf[-1]:
            i += 1
            buf.append(lines[i][7:72])
        trailer = buf[-1].split("END-EXEC", 1)[1].strip()
        buf[-1] = buf[-1].split("END-EXEC", 1)[0]
        block = " ".join(buf)
        for k, v in CVDA.items():
            block = block.replace(k, v)
        stmts = stub(block)
        out.extend(HARNESS_WS if stmts is None else [IND + s for s in stmts])
        if trailer and trailer != ".":
            raise AssertionError("unexpected text after END-EXEC: " + trailer)
        if trailer == "." and stmts is not None:
            out.append(IND + ".")
        i += 1
    text = "\n".join(out) + "\n"
    for k, v in CVDA.items():
        text = text.replace(k, v)
    return text


@unittest.skipUnless(shutil.which("cobc"), "GnuCOBOL (cobc) not installed")
class PolqryAuthorizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        src = os.path.join(cls.tmp, "POLQRY.cbl")
        with open(SOURCE) as fh, open(src, "w") as out:
            out.write(preprocess(fh.read()))
        cls.exe = os.path.join(cls.tmp, "polqry")
        subprocess.run(["cobc", "-x", "-I", COPYBOOKS, "-o", cls.exe, src],
                       check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_pqry(self, grant="", startcode="TD", bridge="", found="Y",
                 agent="AG0001", branch="BR01", api_flag="N"):
        env = dict(os.environ, T_POLNUM="POL-00000001", T_STARTCODE=startcode,
                   T_BRIDGE=bridge, T_USERID="TESTUSR", T_GRANT=grant,
                   T_FOUND=found, T_AGENT=agent, T_BRANCH=branch,
                   T_APIFLAG=api_flag)
        r = subprocess.run([self.exe], env=env, capture_output=True,
                           text=True, check=True)
        return [l.rstrip() for l in r.stdout.splitlines()
                if l.startswith("EVENT ")]

    def assertDenied(self, events, msg):
        self.assertFalse(any(e.startswith("EVENT SEND-MAP") for e in events),
                         events)
        self.assertNotIn("EVENT READ-CUSTOMER", events)
        self.assertIn("EVENT SEND-TEXT " + msg, events)

    def assertShown(self, events):
        self.assertIn("EVENT READ-CUSTOMER", events)
        self.assertIn("EVENT SEND-MAP POL-00000001", events)

    def test_caller_without_entitlement_is_denied(self):
        self.assertDenied(self.run_pqry(grant=""),
                          "NOT AUTHORIZED TO VIEW THIS POLICY")

    def test_other_agents_entitlement_is_denied(self):
        self.assertDenied(self.run_pqry(grant="PAS.PQRY.AGT.AG0002"),
                          "NOT AUTHORIZED TO VIEW THIS POLICY")

    def test_policy_without_agent_or_branch_is_denied(self):
        self.assertDenied(self.run_pqry(grant="PAS.PQRY.AGT.", agent="",
                                        branch=""),
                          "NOT AUTHORIZED TO VIEW THIS POLICY")

    def test_agent_entitlement_shows_policy(self):
        events = self.run_pqry(grant="PAS.PQRY.AGT.AG0001")
        self.assertIn("EVENT QSEC PAS.PQRY.AGT.AG0001", events)
        self.assertShown(events)

    def test_branch_entitlement_shows_policy(self):
        self.assertShown(self.run_pqry(grant="PAS.PQRY.BR.BR01"))

    def test_non_terminal_caller_requires_api_flag(self):
        self.assertDenied(
            self.run_pqry(grant="PAS.PQRY.AGT.AG0001", startcode="D"),
            "POLICY NOT AVAILABLE THROUGH THIS CHANNEL")

    def test_bridge_caller_requires_api_flag(self):
        self.assertDenied(
            self.run_pqry(grant="PAS.PQRY.AGT.AG0001", bridge="CKBR"),
            "POLICY NOT AVAILABLE THROUGH THIS CHANNEL")

    def test_non_terminal_caller_with_api_flag_shows_policy(self):
        self.assertShown(self.run_pqry(grant="PAS.PQRY.AGT.AG0001",
                                       startcode="D", api_flag="Y"))

    def test_policy_not_found_unchanged(self):
        events = self.run_pqry(found="N")
        self.assertEqual(events, ["EVENT SEND-TEXT POLICY NOT FOUND"])


if __name__ == "__main__":
    unittest.main()
