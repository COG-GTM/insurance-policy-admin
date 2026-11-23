"""Shared helpers for the compat gate tests."""

import os

from tests import COMPAT

REPO_ROOT = os.path.dirname(COMPAT)


class FindingsCapture:
    """Stand-in for common.report that records findings instead of printing."""

    def __init__(self):
        self.findings = None

    def __call__(self, check_name, findings):
        self.check_name = check_name
        self.findings = list(findings)
        return 1 if any(f[0] == "FAIL" for f in self.findings) else 0

    def ids(self, severity=None):
        return [
            fid for sev, fid, _ in self.findings if severity is None or sev == severity
        ]


def cobol(*stmts):
    """Format copybook statements into fixed-format source (area A starts at col 8)."""
    return (
        "\n".join(s if s.startswith("      *") else "       " + s for s in stmts) + "\n"
    )
