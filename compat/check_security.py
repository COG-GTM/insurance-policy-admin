#!/usr/bin/env python3
"""Security non-regression on lines added by the PR (diff vs base)."""
import re
from common import git, merge_base, report, exit_with

# Exemptions are anchored to a Maven module's real test tree and local-profile
# config, not any path that merely contains these strings.
LOCAL_OR_TEST = re.compile(
    r"^(?:[^/]+/)?src/(?:test/"
    r"|main/resources/application-local\.(?:properties|ya?ml)$)"
)
SELF = "compat/check_security.py"
SECRET_KEY = re.compile(r"(?i)(password|passwd|secret|api[_-]?key|token)\s*[=:]\s*")
PLACEHOLDER_DEFAULTS = {"changeme", "#{null}"}
MIN_LITERAL_SECRET = 6


def _opens(text, i):
    return text.startswith(("${", "#{"), i)


def _value(line, start):
    """Raw value starting at start: quoted string, or up to whitespace outside ${...}."""
    quote = line[start] if start < len(line) and line[start] in "'\"" else None
    i = start + 1 if quote else start
    begin, depth = i, 0
    while i < len(line):
        c = line[i]
        if _opens(line, i):
            depth += 1
            i += 2
            continue
        if c == "}" and depth:
            depth -= 1
        elif depth == 0 and (c == quote or (not quote and (c.isspace() or c in "'\""))):
            break
        i += 1
    return line[begin:i]


def _split_placeholders(value):
    """Return (literal text outside ${...}, [defaults of every ${NAME:default}, nested too])."""
    literal, defaults, i = [], [], 0
    while i < len(value):
        if not value.startswith("${", i):
            literal.append(value[i])
            i += 1
            continue
        depth, j = 1, i + 2
        while j < len(value) and depth:
            if _opens(value, j):
                depth += 1
                j += 2
                continue
            depth -= value[j] == "}"
            j += 1
        body = value[i + 2:j - 1] if depth == 0 else value[i + 2:]
        _, sep, default = _top_level_colon(body)
        if sep:
            inner_literal, inner_defaults = _split_placeholders(default)
            if inner_literal:
                defaults.append(inner_literal)
            defaults.extend(inner_defaults)
        literal.append(" ")
        i = j
    return "".join(literal), defaults


def _top_level_colon(body):
    depth = 0
    for k, c in enumerate(body):
        if _opens(body, k):
            depth += 1
        elif c == "}" and depth:
            depth -= 1
        elif c == ":" and depth == 0:
            return body[:k], ":", body[k + 1:]
    return body, "", ""


def hardcoded_secrets(line):
    """Secret values hardcoded on a line, as literals or as ${NAME:default} defaults."""
    hits, consumed = [], 0
    for m in SECRET_KEY.finditer(line):
        if m.start() < consumed:
            continue
        raw = _value(line, m.end())
        consumed = m.end() + len(raw)
        literal, defaults = _split_placeholders(raw)
        for run in re.findall(r"[^\s'\"${}<>]+", literal):
            if len(run) >= MIN_LITERAL_SECRET and run.lower() not in PLACEHOLDER_DEFAULTS:
                hits.append(run)
        for d in defaults:
            if d.strip() and d.strip().lower() not in PLACEHOLDER_DEFAULTS:
                hits.append(d.strip())
    return hits


class _SecretRule:
    def search(self, line):
        return bool(hardcoded_secrets(line))


RULES = [
    ("FAIL", "private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), None),
    ("FAIL", "hardcoded-secret", _SecretRule(), LOCAL_OR_TEST),
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
        elif line.startswith("+") and path and path != SELF:
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
