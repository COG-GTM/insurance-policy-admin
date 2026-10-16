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
    ("FAIL", "plaintext-ftp", re.compile(r"(?i)(PGM=FTP\b(?!.*-r\s+TLS\b)|\bftp://)"), re.compile(r"^compat/tests/")),
    ("NOTE", "pii-field", re.compile(r"(?i)\b(ssn_?last4|tax_?id|date_?of_?birth|credit_?score)\b"), re.compile(r"(^sql/|schema\.sql|data\.sql|\.cpy$|/src/test/|^compat/)")),
]


def ftp_steps(text):
    lines = (text or "").splitlines()
    exec_re = re.compile(r"^//([A-Z0-9@$#]+)\s+EXEC\b.*?\bPGM\s*=\s*FTP\b", re.I)
    any_exec_re = re.compile(r"^//[A-Z0-9@$#]+\s+EXEC\b", re.I)
    dd_re = re.compile(r"^//(?:([A-Z0-9@$#]+)\s+)?DD\b(.*)$", re.I)
    exec_starts = [i for i, line in enumerate(lines) if any_exec_re.match(line)]
    steps = []

    for start in exec_starts:
        match = exec_re.match(lines[start])
        if not match:
            continue
        end = next((i for i in exec_starts if i > start), len(lines))
        exec_lines = [lines[start]]
        for line in lines[start + 1:end]:
            if dd_re.match(line):
                break
            if re.match(r"^//\s+", line):
                exec_lines.append(line)
            else:
                break

        dds = {}
        i = start + 1
        while i < end:
            dd_match = dd_re.match(lines[i])
            if not dd_match:
                i += 1
                continue

            ddname = (dd_match.group(1) or "").upper()
            tail = dd_match.group(2)
            statement = [tail]
            j = i + 1
            while j < end and re.match(r"^//\s+", lines[j]) and not dd_re.match(lines[j]):
                statement.append(lines[j][2:])
                j += 1

            dsn_match = re.search(r"\bDSN\s*=\s*(['\"]?)([^,'\"\s]+)\1", " ".join(statement), re.I)
            dsn = dsn_match.group(2) if dsn_match else None
            is_instream = bool(re.search(r"(?:^|[,\s])(?:\*|DATA)(?:[,\s]|$)", tail, re.I))
            data = None
            if is_instream:
                data = []
                data_index = i + 1
                while data_index < end:
                    line = lines[data_index]
                    if line.startswith("/*") or line.startswith("//"):
                        break
                    data.append(line)
                    data_index += 1
            if ddname:
                dds[ddname] = {"dsn": dsn, "data": data}
            i += 1

        steps.append((match.group(1), "\n".join(exec_lines), dds))
    return steps


def ftp_step_issues(text):
    allowlist = {
        "PUT", "GET", "MPUT", "MGET", "APPEND", "QUIT", "CLOSE", "CD",
        "LCD", "CDUP", "PWD", "BINARY", "ASCII", "EBCDIC", "TYPE", "MODE",
        "STRUCTURE", "SITE", "LOCSITE", "DELETE", "MDELETE", "RENAME",
        "MKDIR", "RMDIR", "LS", "DIR", "NOOP", "PROT",
    }
    required_sysftpd = (
        ("SECURE_FTP", "REQUIRED"),
        ("SECURE_MECHANISM", "TLS"),
        ("SECURE_CTRLCONN", "PRIVATE"),
        ("SECURE_DATACONN", "PRIVATE"),
    )
    issues = []

    for stepname, exec_text, dds in ftp_steps(text):
        if not re.search(r"-r\s+TLS\b", exec_text, re.I):
            issues.append((stepname, "PARM does not require TLS (-r TLS)"))

        sysftpd = dds.get("SYSFTPD")
        if not sysftpd or sysftpd["data"] is None or sysftpd["dsn"] is not None:
            issues.append((stepname, "no in-stream SYSFTPD with TLS policy"))
        else:
            policy = "\n".join(sysftpd["data"])
            for name, value in required_sysftpd:
                if not re.search(rf"\b{re.escape(name)}\s+{re.escape(value)}\b", policy, re.I):
                    issues.append((stepname, f"SYSFTPD lacks {name} {value}"))

        if not dds.get("NETRC") or not dds["NETRC"]["dsn"]:
            issues.append((stepname, "no NETRC DD; login would come from the INPUT stream"))

        input_dd = dds.get("INPUT")
        if input_dd and input_dd["data"] is not None:
            first = next((i for i, line in enumerate(input_dd["data"]) if line.strip()), None)
            if first is not None:
                for line_number, line in enumerate(input_dd["data"][first + 1:], start=first + 2):
                    if not line.strip():
                        continue
                    tokens = line.split()
                    command = tokens[0].upper()
                    if command not in allowlist:
                        issues.append((stepname, f"INPUT line {line_number} is not an FTP subcommand (inline login/credential?)"))
                    if command in {"CCC", "CLEAR", "SAFE"} or (
                        command == "PROT" and (len(tokens) < 2 or tokens[1].upper() != "P")
                    ):
                        issues.append((stepname, f"INPUT line {line_number} downgrades TLS protection"))

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
    added = list(added_lines())
    for path, line in added:
        for sev, rule, rx, skip in RULES:
            if (skip is None or not skip.search(path)) and rx.search(line):
                findings.append((sev, f"security:{rule}:{path}", line.strip()[:120]))
    for path in dict.fromkeys(path for path, _ in added if path.lower().endswith(".jcl")):
        text = head_file(path)
        if text is None:
            continue
        for step, message in ftp_step_issues(text):
            findings.append(("FAIL", f"security:ftp-step-insecure:{path}:{step}", message))
    return report("Security non-regression", findings)


if __name__ == "__main__":
    exit_with(main())
