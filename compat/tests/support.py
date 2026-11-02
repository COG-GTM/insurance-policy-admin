"""Shared helpers for the compat gate unit tests."""
import os
import sys
from contextlib import contextmanager
from unittest import mock

COMPAT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
for p in (COMPAT_DIR, os.path.join(COMPAT_DIR, "api")):
    if p not in sys.path:
        sys.path.insert(0, p)


@contextmanager
def captured_findings(module):
    """Replace module.report so main() hands back its findings instead of printing them."""
    captured = []

    def fake_report(name, findings):
        captured.extend(findings)
        return 1 if any(sev == "FAIL" for sev, _, _ in findings) else 0

    with mock.patch.object(module, "report", side_effect=fake_report):
        yield captured


def ids(findings, severity=None):
    return {fid for sev, fid, _ in findings if severity is None or sev == severity}
