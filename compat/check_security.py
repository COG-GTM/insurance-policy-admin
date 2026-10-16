#!/usr/bin/env python3
"""Security non-regression on lines added by the PR (diff vs base)."""
import re
from common import git, merge_base, report, exit_with

LOCAL_OR_TEST = re.compile(r"(application-local\.properties|/src/test/|^compat/)")
RULES = [
    ("FAIL", "private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), None),
    ("FAIL", "hardcoded-secret", re.compile(r"(?i)(password|passwd|secret|api[_-]?key|token)\s*[=:]\s*['\"]?(?!\$\{)(?!changeme\b)[^\s'\"${}<>]{6,}"), LOCAL_OR_TEST),
    ("FAIL", "actuator-exposed", re.compile(r"(?i)(management\.security\.enabled\s*=\s*false|management\.endpoints\.web\.exposure\.include\s*=\s*\*)"), LOCAL_OR_TEST),
    ("FAIL", "h2-console-enabled", re.compile(r"(?i)spring\.h2\.console\.enabled\s*=\s*true"), LOCAL_OR_TEST),
    ("FAIL", "plaintext-ftp", re.compile(r"(?i)(PGM=FTP\b|\bftp://)"), None),
    ("NOTE", "pii-field", re.compile(r"(?i)\b(ssn_?last4|tax_?id|date_?of_?birth|credit_?score)\b"), re.compile(r"(^sql/|schema\.sql|data\.sql|\.cpy$|/src/test/|^compat/)")),
]


def added_lines():
    diff = git("diff", "-U0", "--no-color", f"{merge_base()}...HEAD")
    path = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("+") and path:
            yield path, line[1:]


def main():
    findings = []
    for path, line in added_lines():
        for sev, rule, rx, skip in RULES:
            if (skip is None or not skip.search(path)) and rx.search(line):
                findings.append((sev, f"security:{rule}:{path}", line.strip()[:120]))
    return report("Security non-regression", findings)


if __name__ == "__main__":
    exit_with(main())
