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


SYMBOL_REF = re.compile(r"&([A-Z@$#][A-Z0-9@$#]*)\.?", re.I)
UNPROVABLE = object()


def symbols_at_each_exec(lines, exec_starts):
    """SET values in effect at each EXEC; PROC parameters can be overridden by a caller."""
    symbols, at_exec = {}, {}
    starts = set(exec_starts)
    for i, line in enumerate(lines):
        if i in starts:
            at_exec[i] = dict(symbols)
        m = re.match(r"^//[A-Z0-9@$#]*\s+(SET|PROC)\b(.*)$", line, re.I)
        if not m:
            continue
        operands = re.sub(r"'(?:[^']|'')*'", lambda q: q.group(0).replace(" ", "\0"), m.group(2).strip())
        operands = operands.split(" ", 1)[0].replace("\0", " ")
        for name, value in re.findall(r"([A-Z@$#][A-Z0-9@$#]*)=('(?:[^']|'')*'|[^,]*)", operands, re.I):
            if m.group(1).upper() == "PROC":
                symbols[name.upper()] = UNPROVABLE
            else:
                symbols[name.upper()] = value[1:-1] if value.startswith("'") else value
    return at_exec


def resolve_program(program, symbols):
    """The literal program name, or None when it cannot be proven from this file."""
    for _ in range(10):
        if "&" not in program:
            return program
        unprovable = False

        def substitute(m):
            nonlocal unprovable
            value = symbols.get(m.group(1).upper(), UNPROVABLE)
            if value is UNPROVABLE:
                unprovable = True
                return m.group(0)
            return value

        program = SYMBOL_REF.sub(substitute, program)
        if unprovable:
            return None
    return None


def ftp_steps(text):
    lines = (text or "").splitlines()
    any_exec_re = re.compile(r"^//[A-Z0-9@$#]+\s+EXEC\b", re.I)
    dd_re = re.compile(r"^//(?:([A-Z0-9@$#]+)\s+)?\s*DD\b(.*)$", re.I)
    exec_starts = [i for i, line in enumerate(lines) if any_exec_re.match(line)]
    symbols = symbols_at_each_exec(lines, exec_starts)
    steps = []

    for start in exec_starts:
        end = next((i for i in exec_starts if i > start), len(lines))
        exec_lines = [lines[start]]
        for line in lines[start + 1:end]:
            if dd_re.match(line):
                break
            if re.match(r"^//\s+", line):
                exec_lines.append(line)
            else:
                break

        exec_text = "\n".join(exec_lines)
        exec_for_match = "\n".join(line[2:] if line.startswith("//") else line for line in exec_lines)
        exec_for_match = re.sub(r"'(?:[^']|'')*'", "''", exec_for_match)
        program = re.search(r"(?:^|[\s,])PGM\s*=\s*([^\s,]+)", exec_for_match, re.I)
        if not program:
            continue
        # A symbol without a SET value provable at this step (PROC parameter, system
        # symbol, INCLUDE member) could be FTP, so it gets the full check.
        resolved = resolve_program(program.group(1), symbols[start])
        if resolved is not None and resolved.upper() != "FTP":
            continue

        dds = {}
        i = start + 1
        previous_ddname = None
        while i < end:
            dd_match = dd_re.match(lines[i])
            if not dd_match:
                i += 1
                continue

            ddname = (dd_match.group(1) or "").upper()
            if not ddname:
                if previous_ddname in dds:
                    dds[previous_ddname]["concat"] = True
                i += 1
                continue

            tail = dd_match.group(2)
            statement = [tail]
            j = i + 1
            while j < end and re.match(r"^//\s+", lines[j]) and not dd_re.match(lines[j]):
                statement.append(lines[j][2:])
                j += 1

            dsn_match = re.search(r"\bDSN\s*=\s*['\"]?([^,'\"\s]+)", " ".join(statement), re.I)
            dsn = dsn_match.group(1) if dsn_match else None
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
            dds[ddname] = {"dsn": dsn, "data": data, "concat": False}
            previous_ddname = ddname
            i += 1

        stepname = re.match(r"^//([A-Z0-9@$#]+)\s+EXEC\b", lines[start], re.I).group(1)
        steps.append((stepname, exec_text, dds))
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
        if not re.search(r"\(\s*EXIT\b", exec_text, re.I):
            issues.append((stepname, "PARM lacks (EXIT; a refused TLS handshake or failed PUT would not end the step with a non-zero return code"))

        sysftpd = dds.get("SYSFTPD")
        if not sysftpd or sysftpd["data"] is None or sysftpd["dsn"] is not None:
            issues.append((stepname, "no in-stream SYSFTPD with TLS policy"))
        else:
            directives = {}
            for line in sysftpd["data"]:
                directive = line.split(";", 1)[0].split()
                if directive:
                    value = " ".join(directive[1:]).upper()
                    directives.setdefault(directive[0].upper(), []).append(value)
            for name, value in required_sysftpd:
                values = directives.get(name, [])
                if not values:
                    issues.append((stepname, f"SYSFTPD lacks {name} {value}"))
                if any(item != value for item in values):
                    issues.append((stepname, f"SYSFTPD overrides {name} with a non-{value} value"))

        if sysftpd and sysftpd["concat"]:
            issues.append((stepname, "SYSFTPD has a concatenated dataset; TLS policy/commands must be fully in-stream"))

        if not dds.get("NETRC") or not dds["NETRC"]["dsn"]:
            issues.append((stepname, "no NETRC DD; login would come from the INPUT stream"))

        command_ddnames = [name for name in ("INPUT", "SYSIN") if name in dds]
        for ddname in command_ddnames:
            if dds.get(ddname, {}).get("concat"):
                issues.append((stepname, f"{ddname} has a concatenated dataset; TLS policy/commands must be fully in-stream"))

        if not command_ddnames:
            issues.append((stepname, "FTP commands must come from an in-stream INPUT DD so they can be reviewed"))
        for command_ddname in command_ddnames:
            command_dd = dds[command_ddname]
            if command_dd["data"] is None or command_dd["dsn"] is not None or command_dd["concat"]:
                if command_ddname == "INPUT":
                    message = "FTP commands must come from an in-stream INPUT DD so they can be reviewed"
                else:
                    message = "SYSIN must be in-stream so FTP commands can be reviewed"
                issues.append((stepname, message))
                continue

            host_seen = False
            for line_number, line in enumerate(command_dd["data"], start=1):
                if not line.strip():
                    continue
                if not host_seen:
                    host_seen = True
                    continue
                tokens = line.split()
                command = tokens[0].upper()
                if command not in allowlist:
                    issues.append((stepname, f"{command_ddname} line {line_number} is not an FTP subcommand (inline login/credential?)"))
                if command in {"CCC", "CLEAR", "SAFE"} or (
                    command == "PROT" and (len(tokens) < 2 or tokens[1].upper() != "P")
                ):
                    issues.append((stepname, f"{command_ddname} line {line_number} downgrades TLS protection"))

    return issues


def added_lines():
    diff = git("diff", "-U0", "--no-color", f"{merge_base()}...HEAD")
    path = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("+") and path and path != SELF:
            yield path, line[1:]


FTP_URL = re.compile(r"(?i)\bftp://")


def security_findings(added, read_file):
    findings = []
    for path, line in added:
        is_jcl = path.lower().endswith(".jcl")
        for sev, rule, rx, skip in RULES:
            if rule == "plaintext-ftp" and is_jcl:
                # PGM=FTP steps in JCL are judged whole by ftp_step_issues;
                # a single line cannot show a PARM on a continuation.
                rx = FTP_URL
            if (skip is None or not skip.search(path)) and rx.search(line):
                findings.append((sev, f"security:{rule}:{path}", line.strip()[:120]))
    for path in dict.fromkeys(path for path, _ in added if path.lower().endswith(".jcl")):
        text = read_file(path)
        if text is None:
            continue
        for step, message in ftp_step_issues(text):
            findings.append(("FAIL", f"security:ftp-step-insecure:{path}:{step}", message))
    return findings


def main():
    return report("Security non-regression", security_findings(list(added_lines()), head_file))


if __name__ == "__main__":
    exit_with(main())
