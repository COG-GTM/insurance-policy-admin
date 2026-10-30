#!/usr/bin/env python3
"""Security non-regression on lines added by the PR (diff vs base)."""
import re
from common import git, merge_base, report, exit_with, head_file

LOCAL_OR_TEST = re.compile(r"(application-local\.properties|/src/test/)")
SELF = "compat/check_security.py"
RULES = [
    ("FAIL", "private-key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), None),
    ("FAIL", "hardcoded-secret", re.compile(r"(?i)(password|passwd|secret|api[_-]?key|token)\s*[=:]\s*['\"]?(?!\$\{)(?!changeme\b)[^\s'\"${}<>]{6,}"), LOCAL_OR_TEST),
    ("FAIL", "actuator-exposed", re.compile(r"(?i)(management\.security\.enabled\s*=\s*false|management\.endpoints\.web\.exposure\.include\s*=\s*\*)"), LOCAL_OR_TEST),
    ("FAIL", "h2-console-enabled", re.compile(r"(?i)spring\.h2\.console\.enabled\s*=\s*true"), LOCAL_OR_TEST),
    ("FAIL", "plaintext-ftp", re.compile(r"(?i)(PGM=FTP\b(?!.*-r\s+TLS\b)|\bftp://)"), None),
    ("NOTE", "pii-field", re.compile(r"(?i)\b(ssn_?last4|tax_?id|date_?of_?birth|credit_?score)\b"), re.compile(r"(^sql/|schema\.sql|data\.sql|\.cpy$|/src/test/|^compat/)")),
]

TLS_FTP_EXEC = re.compile(r"(?i)^//(\S+)\s+EXEC\s+PGM=FTP\b.*-r\s+TLS\b")
REQUIRED_FTP_DATA = {
    "SECURE_FTP": "REQUIRED",
    "SECURE_MECHANISM": "TLS",
    "SECURE_CTRLCONN": "PRIVATE",
    "SECURE_DATACONN": "PRIVATE",
}
TLS_DOWNGRADE_CMDS = {"CCC", "CLEAR", "SAFE"}


def tls_ftp_step_issues(text, step):
    """Issues for an FTP step whose EXEC line claims TLS (-r TLS).

    The step's TLS policy must be fully visible in the job: an in-stream
    SYSFTPD setting REQUIRED_FTP_DATA (and nothing weaker), a NETRC dataset
    for the login, and no downgrade subcommands in the in-stream INPUT."""
    lines = text.splitlines()
    start = next((i for i, l in enumerate(lines) if TLS_FTP_EXEC.match(l) and TLS_FTP_EXEC.match(l).group(1) == step), None)
    if start is None:
        return [f"{step}: FTP step not found"]
    end = next((i for i in range(start + 1, len(lines)) if re.match(r"(?i)^//\S+\s+EXEC\b", lines[i])), len(lines))
    dds, current = {}, None
    for line in lines[start + 1:end]:
        dd = re.match(r"(?i)^//(\S*)\s+DD\b(.*)", line)
        if dd:
            name = dd.group(1).upper()
            if not name:
                return [f"{step}: concatenated DD after {current}; TLS policy/commands must be in-stream"]
            instream = re.match(r"\s*(\*|DATA)\s*(,|$)", dd.group(2).upper())
            dds[name] = {"spec": dd.group(2), "data": [] if instream else None}
            current = name
        elif line.startswith("//") or line.startswith("/*"):
            current = None
        elif current and dds[current]["data"] is not None:
            dds[current]["data"].append(line)

    issues = []
    sysftpd = dds.get("SYSFTPD")
    if not sysftpd or sysftpd["data"] is None:
        issues.append(f"{step}: no in-stream SYSFTPD with the TLS policy")
    else:
        seen = {}
        for line in sysftpd["data"]:
            words = line.split(";", 1)[0].split()
            if words:
                seen.setdefault(words[0].upper(), []).append(" ".join(words[1:]).upper())
        for key, value in REQUIRED_FTP_DATA.items():
            values = seen.get(key, [])
            if not values or any(v != value for v in values):
                issues.append(f"{step}: SYSFTPD must set {key} {value} (and only that)")
    netrc = dds.get("NETRC")
    if not netrc or not re.search(r"(?i)\bDSN=", netrc["spec"]):
        issues.append(f"{step}: no NETRC dataset; login would be in the JCL stream")
    command_dd = dds.get("INPUT")
    if not command_dd or command_dd["data"] is None:
        issues.append(f"{step}: FTP commands must be an in-stream INPUT DD")
    else:
        commands = [l.split() for l in command_dd["data"] if l.strip()][1:]
        for words in commands:
            cmd = words[0].upper()
            if cmd in TLS_DOWNGRADE_CMDS or (cmd == "PROT" and words[1:2] != ["P"]):
                issues.append(f"{step}: INPUT subcommand {cmd} downgrades TLS")
    return issues


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
        exec_match = TLS_FTP_EXEC.match(line)
        if exec_match and path.lower().endswith(".jcl"):
            for issue in tls_ftp_step_issues(head_file(path) or "", exec_match.group(1)):
                findings.append(("FAIL", f"security:plaintext-ftp:{path}", issue))
    return report("Security non-regression", findings)


if __name__ == "__main__":
    exit_with(main())
