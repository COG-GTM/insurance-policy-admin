#!/usr/bin/env python3
"""Generate and run fixed-format COBOL characterization drivers."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from decimal import Decimal
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PARITY = ROOT / "parity"
EXEC_BLOCK = re.compile(r"EXEC\s+(?:SQL|CICS)\b.*?END-EXEC", re.IGNORECASE | re.DOTALL)
COPY_STMT = re.compile(r"^\s*COPY\s+['\"]?([\w-]+)", re.IGNORECASE | re.MULTILINE)


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)


def dump_json(value) -> str:
    text = json.dumps(value, indent=2, ensure_ascii=False, default=_json_default)
    text = re.sub(r'"__PX_DECIMAL__(-?\d+(?:\.\d+)?)__"', r"\1", text)
    return text + "\n"


def _json_default(value):
    if isinstance(value, Decimal):
        return f"__PX_DECIMAL__{format(value, 'f')}__"
    raise TypeError(f"Cannot encode {type(value).__name__}")


def cobol_name(prefix: str, path: str) -> str:
    separated = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", path)
    name = re.sub(r"[^A-Za-z0-9]+", "-", separated).strip("-").upper()
    return f"{prefix}-{name}"


def fields(spec: dict, section: str):
    return spec.get(section, [])


def width(field: dict) -> int:
    kind = field["type"]
    if kind == "date":
        return 8
    if kind == "text":
        return field["length"]
    sign = 1
    if kind == "int":
        return field["digits"] + sign
    if kind == "decimal":
        return field["integerDigits"] + field["scale"] + sign
    raise ValueError(f"Unknown field type: {kind}")


def picture(field: dict) -> str:
    kind = field["type"]
    if kind == "date":
        return "PIC 9(8)"
    if kind == "text":
        return f"PIC X({field['length']})"
    if kind == "int":
        return f"PIC S9({field['digits']}) SIGN IS LEADING SEPARATE"
    if kind == "decimal":
        return (
            f"PIC S9({field['integerDigits']})V9({field['scale']}) "
            "SIGN IS LEADING SEPARATE"
        )
    raise ValueError(f"Unknown field type: {kind}")


def field_max(field: dict) -> Decimal:
    if field["type"] == "int":
        return Decimal(10) ** field["digits"] - 1
    return Decimal(10) ** field["integerDigits"] - Decimal(1).scaleb(
        -field.get("scale", 0)
    )


def encode_field(value, field: dict) -> str:
    kind = field["type"]
    label = field.get("name", field.get("path", "field"))
    if kind == "text":
        text = str(value)
        if len(text) > field["length"]:
            raise ValueError(f"{label} exceeds text({field['length']})")
        return text.ljust(field["length"])
    if kind == "date":
        text = str(value)
        if not re.fullmatch(r"\d{8}", text):
            raise ValueError(f"{label} must be an eight-digit date string")
        return text
    number = Decimal(str(value))
    if kind == "int":
        if number != number.to_integral_value():
            raise ValueError(f"{label} must be an integer")
        if abs(number) > field_max(field):
            raise ValueError(f"{label} is outside int({field['digits']})")
        magnitude = f"{abs(int(number)):0{field['digits']}d}"
    elif kind == "decimal":
        scale = field["scale"]
        scaled = number.quantize(Decimal(1).scaleb(-scale))
        if scaled != number or abs(number) > field_max(field):
            raise ValueError(
                f"{label} is outside decimal"
                f"({field['integerDigits']},{scale})"
            )
        magnitude = f"{abs(scaled):0{field['integerDigits'] + scale + 1}.{scale}f}"
        magnitude = magnitude.replace(".", "")
    else:
        raise ValueError(f"Unknown field type: {kind}")
    return ("-" if number < 0 else "+") + magnitude


def decode_field(raw: str, field: dict):
    kind = field["type"]
    if kind in ("text", "date"):
        return raw.rstrip() if kind == "text" else raw
    sign, digits = raw[0], raw[1:]
    value = Decimal(digits)
    if sign == "-":
        value = -value
    if kind == "int":
        return int(value)
    return value.scaleb(-field["scale"])


def get_path(value, path: str):
    for part in re.findall(r"[^.\[\]]+", path):
        if part.isdigit() and isinstance(value, list):
            value = value[int(part)]
        else:
            value = value[part]
    return value


def set_path(value, path: str, replacement):
    parts = re.findall(r"[^.\[\]]+", path)
    node = value
    for part in parts[:-1]:
        node = node[int(part)] if part.isdigit() else node[part]
    last = parts[-1]
    if last.isdigit():
        node[int(last)] = replacement
    else:
        node[last] = replacement


def source_paths(program: str) -> list[Path]:
    source = ROOT / "cobol" / "programs" / f"{program}.cbl"
    if not source.is_file():
        raise ValueError(f"Missing legacy program: {source}")
    names = COPY_STMT.findall(source.read_text(encoding="utf-8"))
    copies = []
    for name in names:
        match = next((ROOT / "cobol" / "copybooks").glob(f"{name}.*"), None)
        if match is None:
            raise ValueError(f"Missing copybook {name} used by {program}")
        copies.append(match)
    return [source, *copies]


def source_lock(program: str) -> str:
    return source_lock_paths(source_paths(program), ROOT)


def source_lock_paths(paths: list[Path], root: Path) -> str:
    rows = []
    for path in paths:
        relative = path.relative_to(root).as_posix()
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        rows.append(f"{digest}  {relative}")
    return "\n".join(rows) + "\n"


def check_locks():
    failures = []
    for spec_path in sorted((PARITY / "programs").glob("*/spec.json")):
        program = spec_path.parent.name
        lock = spec_path.parent / "source.lock"
        if not lock.exists() or lock.read_text(encoding="utf-8") != source_lock(program):
            failures.append(f"{program}: legacy source drifted; review and re-run golden, then lock")
    return failures


def validate_fixture(fixture: dict, spec: dict, path: Path):
    defaults = merge_values(
        spec.get("defaults", {}), {"seed": spec.get("seedDefaults", {})}
    )
    input_value = merge_values(defaults, {"seed": fixture.get("seed", {})})
    input_value = merge_values(input_value, fixture.get("input", {}))
    for field in fields(spec, "inputs"):
        try:
            encode_field(get_path(input_value, field["path"]), field)
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(f"{path}: input.{field['path']}: {error}") from error
    policy_fields = fields(spec, "policyInputs")
    if policy_fields:
        policies = input_value.get("policies", [])
        if not isinstance(policies, list):
            raise ValueError(f"{path}: input.policies must be an array")
        for index, policy in enumerate(policies):
            for field in policy_fields:
                try:
                    encode_field(policy[field["name"]], field)
                except (KeyError, TypeError, ValueError) as error:
                    raise ValueError(
                        f"{path}: input.policies[{index}].{field['name']}: {error}"
                    ) from error
    return input_value


def merge_values(base, override):
    if not isinstance(base, dict) or not isinstance(override, dict):
        return override
    merged = dict(base)
    for key, value in override.items():
        merged[key] = merge_values(merged[key], value) if key in merged else value
    return merged


def fixture_paths(program: str):
    return sorted((PARITY / "fixtures" / program).glob("*.json"))


def golden_paths(program: str):
    return sorted((PARITY / "golden" / program).glob("*.json"))


def check_fixtures():
    failures = check_locks()
    for spec_path in sorted((PARITY / "programs").glob("*/spec.json")):
        program = spec_path.parent.name
        spec = load_json(spec_path)
        fixtures = fixture_paths(program)
        goldens = golden_paths(program)
        fixture_names = {path.stem for path in fixtures}
        golden_names = {path.stem for path in goldens}
        for name in sorted(fixture_names - golden_names):
            failures.append(f"{program}/{name}: missing golden")
        for name in sorted(golden_names - fixture_names):
            failures.append(f"{program}/{name}: golden has no fixture")
        for path in fixtures:
            try:
                fixture = load_json(path)
                validate_fixture(fixture, spec, path)
            except (ValueError, json.JSONDecodeError) as error:
                failures.append(str(error))
        for path in goldens:
            try:
                golden = load_json(path)
                if golden.get("program") != program or golden.get("fixture") != path.stem:
                    failures.append(f"{path}: golden program/fixture does not match its filename")
            except (ValueError, json.JSONDecodeError) as error:
                failures.append(f"{path}: {error}")
    return failures


def fixed_format(text: str) -> str:
    """Wrap generated COBOL statements to fixed-format source columns 8-72."""
    result = []
    for line in text.splitlines():
        if not line:
            result.append("")
            continue
        indent = line[: len(line) - len(line.lstrip())]
        body = line.strip()
        prefix = indent if len(indent) >= 7 else "       "
        width_available = 72 - len(prefix)
        while len(body) > width_available:
            split_at = body.rfind(" ", 0, width_available + 1)
            if split_at < 12:
                split_at = width_available
            result.append(prefix + body[:split_at])
            body = "           " + body[split_at:].lstrip()
            prefix = "       "
            width_available = 72 - len(prefix)
        result.append(prefix + body)
    return "\n".join(result) + "\n"


def normalize_exec(block: str) -> str:
    return re.sub(r"\s+", " ", block).strip().upper()


def replace_exec_blocks(procedure: str, rules: list[dict], program: str):
    matches = list(EXEC_BLOCK.finditer(procedure))
    counts = {rule["name"]: 0 for rule in rules}
    output = []
    cursor = 0
    for match in matches:
        block = match.group(0)
        normalized = normalize_exec(block)
        found = [
            rule for rule in rules
            if re.search(rule["pattern"], normalized, re.IGNORECASE)
        ]
        if len(found) != 1:
            raise ValueError(
                f"{program}: EXEC block has {len(found)} matching execRules: {normalized}"
            )
        rule = found[0]
        counts[rule["name"]] += 1
        replacement = (
            "CONTINUE"
            if rule.get("continue")
            else f"PERFORM PX-STUB-{rule['name'].upper()}"
        )
        output.append(procedure[cursor : match.start()])
        output.append(replacement)
        cursor = match.end()
    output.append(procedure[cursor:])
    for rule in rules:
        if counts[rule["name"]] != rule["expectCount"]:
            raise ValueError(
                f"{program}: EXEC rule {rule['name']} matched {counts[rule['name']]}; "
                f"expected {rule['expectCount']}"
            )
    return "".join(output)


def replace_rewrites(text: str, rewrites: list[dict], program: str):
    for rewrite in rewrites:
        text, count = re.subn(
            rewrite["pattern"], rewrite["replacement"], text, flags=re.IGNORECASE
        )
        if count != rewrite["expectCount"]:
            raise ValueError(
                f"{program}: rewrite matched {count}; expected "
                f"{rewrite['expectCount']}: {rewrite['pattern']}"
            )
    return text


def extract_sections(source: str, program: str):
    data_at = re.search(r"^\s*DATA DIVISION\.", source, re.MULTILINE | re.IGNORECASE)
    proc_at = re.search(r"^\s*PROCEDURE DIVISION\.", source, re.MULTILINE | re.IGNORECASE)
    if not data_at or not proc_at:
        raise ValueError(f"{program}: DATA or PROCEDURE DIVISION missing")
    ident = source[: re.search(r"^\s*ENVIRONMENT DIVISION\.", source, re.MULTILINE).start()]
    env = source[re.search(r"^\s*ENVIRONMENT DIVISION\.", source, re.MULTILINE).start() : data_at.start()]
    data = source[data_at.end() : proc_at.start()]
    procedure = source[proc_at.end() :]
    file_match = re.search(
        r"^\s*FILE SECTION\.(.*?)(?=^\s*WORKING-STORAGE SECTION\.)",
        data,
        re.MULTILINE | re.DOTALL | re.IGNORECASE,
    )
    fd = file_match.group(1) if file_match else ""
    ws_match = re.search(
        r"^\s*WORKING-STORAGE SECTION\.(.*?)(?=^\s*LINKAGE SECTION\.|\Z)",
        data,
        re.MULTILINE | re.DOTALL | re.IGNORECASE,
    )
    ws = ws_match.group(1) if ws_match else ""
    linkage_match = re.search(
        r"^\s*LINKAGE SECTION\.(.*)\Z",
        data,
        re.MULTILINE | re.DOTALL | re.IGNORECASE,
    )
    linkage = linkage_match.group(1) if linkage_match else ""
    return ident, env, fd, ws, linkage, procedure


def slot_name(prefix: str, kind: str, name: str) -> str:
    return cobol_name(prefix, f"{kind}-{name}")


def io_declarations(spec: dict):
    in_fields = fields(spec, "inputs")
    policy_fields = fields(spec, "policyInputs")
    output_fields = []
    for out_name, output in spec.get("outputs", {}).items():
        for field in output["fields"]:
            output_fields.append((out_name, field))
    state_fields = fields(spec, "state")
    event_fields = [{"name": "eventValue", "type": "text", "length": 48}]
    return in_fields, policy_fields, output_fields, state_fields, event_fields


def declare_fields(fields_list: list[dict], prefix: str, name_for):
    rows = []
    for field in fields_list:
        rows.append(f"01 {name_for(field)} {picture(field)}.")
    return "\n".join(rows)


def make_input_code(spec: dict):
    in_fields, policy_fields, _, _, _ = io_declarations(spec)
    rec_width = max(
        sum(width(field) for field in in_fields),
        sum(width(field) for field in policy_fields),
        1,
    )
    cursor = 1
    rows = ["PX-LOAD-INPUT."]
    for field in in_fields:
        size = width(field)
        var = cobol_name("PX-IN", field["path"])
        source = f"PX-INPUT-REC({cursor}:{size})"
        if field["type"] == "decimal" and field["scale"]:
            divisor = 10**field["scale"]
            rows.append(f"COMPUTE {var} = FUNCTION NUMVAL({source}) / {divisor}")
        else:
            rows.append(f"MOVE {source} TO {var}")
        cursor += size
    rows.append("EXIT.")
    return rows, rec_width


def output_layout(spec: dict):
    slots = []
    for out_name, output in spec.get("outputs", {}).items():
        for field in output["fields"]:
            slots.append((out_name, field))
    for field in fields(spec, "state"):
        slots.append(("state", field))
    slots.append(("event", {"name": "eventValue", "type": "text", "length": 48}))
    slots.append(("return", {"name": "returnCode", "type": "int", "digits": 4}))
    return slots


def generate_emitters(spec: dict, slots):
    declarations = ["01 PX-OUTPUT-BUFFER."]
    declarations.append("05 PX-OUTPUT-TAG PIC X(16).")
    for kind, field in slots:
        declarations.append(f"05 {slot_name('PX-OUT', kind, field['name'])} {picture(field)}.")
    out_width = 16 + sum(width(field) for _, field in slots)
    paragraphs = []
    for out_name, output in spec.get("outputs", {}).items():
        paragraphs.append(f"PX-EMIT-{out_name.upper()}.")
        paragraphs.append("INITIALIZE PX-OUTPUT-BUFFER")
        paragraphs.append(f"MOVE '{out_name}' TO PX-OUTPUT-TAG")
        for field in output["fields"]:
            target = slot_name("PX-OUT", out_name, field["name"])
            paragraphs.append(f"MOVE {field['source']} TO {target}")
        paragraphs.append("WRITE PX-OUTPUT-REC FROM PX-OUTPUT-BUFFER")
        paragraphs.append("EXIT.")
    paragraphs += [
        "PX-EMIT-SCREEN.",
        "INITIALIZE PX-OUTPUT-BUFFER",
        "MOVE 'EVENT-SCREEN' TO PX-OUTPUT-TAG",
        "MOVE PX-EVENT-VALUE TO PX-OUT-EVENT-EVENT-VALUE",
        "WRITE PX-OUTPUT-REC FROM PX-OUTPUT-BUFFER",
        "EXIT.",
    ]
    state = spec.get("state", [])
    paragraphs += [
        "PX-FINISH.",
        "IF PX-FINISHED NOT = 'Y'",
    ]
    for out_name in spec.get("emitOnFinish", []):
        paragraphs.append(f"PERFORM PX-EMIT-{out_name.upper()}")
    paragraphs += [
        "INITIALIZE PX-OUTPUT-BUFFER",
        "MOVE 'STATE' TO PX-OUTPUT-TAG",
    ]
    for field in state:
        paragraphs.append(
            f"MOVE {field['source']} TO {slot_name('PX-OUT', 'state', field['name'])}"
        )
    paragraphs += [
        "WRITE PX-OUTPUT-REC FROM PX-OUTPUT-BUFFER",
        "INITIALIZE PX-OUTPUT-BUFFER",
        "MOVE 'RETURN' TO PX-OUTPUT-TAG",
        "MOVE RETURN-CODE TO PX-OUT-RETURN-RETURN-CODE",
        "WRITE PX-OUTPUT-REC FROM PX-OUTPUT-BUFFER",
        "MOVE 'Y' TO PX-FINISHED",
        "CLOSE PX-INPUT-FILE PX-OUTPUT-FILE",
        "END-IF",
        "EXIT.",
    ]
    return declarations, paragraphs, out_width


def build_driver(spec: dict):
    program = spec["program"]
    main = (ROOT / "cobol" / "programs" / f"{program}.cbl").read_text(encoding="utf-8")
    ident, env, fd, ws, linkage, procedure = extract_sections(main, program)
    env = replace_rewrites(env, spec.get("environmentRewrites", []), program)
    procedure = replace_exec_blocks(procedure, spec.get("execRules", []), program)
    procedure = replace_rewrites(procedure, spec.get("rewrites", []), program)
    data_replace = re.compile(
        r"EXEC SQL INCLUDE SQLCA END-EXEC\.?", re.IGNORECASE
    )
    ws, sqlca_count = data_replace.subn("       01 SQLCODE PIC S9(9) COMP-5.", ws)
    if sqlca_count != 1:
        raise ValueError(f"{program}: expected one SQLCA include, found {sqlca_count}")
    in_fields, policy_fields, _, _, _ = io_declarations(spec)
    input_rows, input_width = make_input_code(spec)
    slots = output_layout(spec)
    emit_declarations, emit_paragraphs, output_width = generate_emitters(spec, slots)
    fixed = fields(spec, "inputs") + fields(spec, "policyInputs")
    vars_decl = declare_fields(
        fixed,
        "PX-IN",
        lambda field: cobol_name(
            "PX-IN", field["path"] if "path" in field else field["name"]
        ),
    )
    ws_extra = [
        "01 PX-INPUT-PATH PIC X(256).",
        "01 PX-OUTPUT-PATH PIC X(256).",
        "01 PX-FILE-STATUS PIC X(2).",
        "01 PX-INPUT-REC PIC X(4096).",
        "01 PX-FINISHED PIC X VALUE 'N'.",
        "01 PX-END-OF-INPUT PIC X VALUE 'N'.",
        "01 PX-EVENT-VALUE PIC X(48).",
        vars_decl,
        "\n".join(emit_declarations),
    ]
    if program == "PREMBAT":
        ws_extra.append("01 PX-REPORT-PATH PIC X(256).")
    generated_fd = (
        "\n       FD  PX-INPUT-FILE.\n"
        "       01  PX-INPUT-FILE-REC PIC X(4096).\n"
        "       FD  PX-OUTPUT-FILE.\n"
        f"       01  PX-OUTPUT-REC PIC X({output_width}).\n"
    )
    env_addition = (
        "\n           SELECT PX-INPUT-FILE ASSIGN TO DYNAMIC PX-INPUT-PATH\n"
        "               ORGANIZATION IS LINE SEQUENTIAL\n"
        "               FILE STATUS IS PX-FILE-STATUS.\n"
        "           SELECT PX-OUTPUT-FILE ASSIGN TO DYNAMIC PX-OUTPUT-PATH\n"
        "               ORGANIZATION IS LINE SEQUENTIAL\n"
        "               FILE STATUS IS PX-FILE-STATUS.\n"
    )
    file_control = re.search(r"^\s*FILE-CONTROL\.", env, re.MULTILINE | re.IGNORECASE)
    if file_control:
        env = env[: file_control.end()] + env_addition + env[file_control.end() :]
    else:
        env += "\n       INPUT-OUTPUT SECTION.\n       FILE-CONTROL.\n" + env_addition
    driver = [
        "PX-DRIVER.",
        "ACCEPT PX-INPUT-PATH FROM ENVIRONMENT 'PX_INPUT'",
        "ACCEPT PX-OUTPUT-PATH FROM ENVIRONMENT 'PX_OUTPUT'",
    ]
    if program == "PREMBAT":
        driver.append("ACCEPT PX-REPORT-PATH FROM ENVIRONMENT 'PX_REPORT'")
    driver.extend([
        "OPEN INPUT PX-INPUT-FILE",
        "OPEN OUTPUT PX-OUTPUT-FILE",
        "READ PX-INPUT-FILE INTO PX-INPUT-REC",
        "AT END MOVE 'Y' TO PX-END-OF-INPUT",
        "END-READ",
        "IF PX-END-OF-INPUT = 'N'",
        "PERFORM PX-LOAD-INPUT",
    ])
    for field in spec.get("seedMoves", []):
        driver.append(
            f"MOVE {cobol_name('PX-IN', field['path'])} TO {field['source']}"
        )
    driver.extend([
        "END-IF",
        "PERFORM 0000-MAIN-LOGIC",
        "PERFORM PX-FINISH",
        "STOP RUN.",
        *input_rows,
    ])
    if spec.get("batched"):
        driver.extend(["PX-READ-NEXT-POLICY.", "READ PX-INPUT-FILE INTO PX-INPUT-REC"])
        cursor = 1
        driver.append("AT END MOVE 'Y' TO PX-END-OF-INPUT")
        driver.append("NOT AT END")
        for field in policy_fields:
            size = width(field)
            var = cobol_name("PX-IN", field["name"])
            source = f"PX-INPUT-REC({cursor}:{size})"
            if field["type"] == "decimal" and field["scale"]:
                driver.append(
                    f"COMPUTE {var} = FUNCTION NUMVAL({source}) / "
                    f"{10**field['scale']}"
                )
            else:
                driver.append(f"MOVE {source} TO {var}")
            cursor += size
        driver.extend(["END-READ", "EXIT."])
    driver.extend(emit_paragraphs)
    code = [
        ident,
        env,
        "       DATA DIVISION.",
        "       FILE SECTION.",
        fd,
        generated_fd,
        "       WORKING-STORAGE SECTION.",
        ws + "\n" + fixed_format("\n".join(ws_extra)),
        "       LINKAGE SECTION.",
        linkage,
        "       PROCEDURE DIVISION.",
        fixed_format("\n".join(driver)),
        procedure,
        fixed_format((PARITY / "programs" / program / "stubs.cpy").read_text(encoding="utf-8")),
    ]
    return "\n".join(code), input_width, output_width, slots


def flatten_input(value: dict, spec: dict):
    return [get_path(value, field["path"]) for field in fields(spec, "inputs")]


def input_line(values: list, fields_list: list[dict]):
    return "".join(encode_field(value, field) for value, field in zip(values, fields_list))


def fixture_input_lines(fixture: dict, spec: dict):
    value = validate_fixture(fixture, spec, Path(fixture.get("_path", "fixture")))
    lines = [input_line(flatten_input(value, spec), fields(spec, "inputs"))]
    if spec.get("batched"):
        policy_fields = fields(spec, "policyInputs")
        lines.extend(
            input_line([policy[field["name"]] for field in policy_fields], policy_fields)
            for policy in value.get("policies", [])
        )
    return lines


def write_input(path: Path, fixture: dict, spec: dict):
    path.write_text("\n".join(fixture_input_lines(fixture, spec)) + "\n", encoding="ascii")


def output_layout_offsets(slots):
    offsets = {}
    cursor = 16
    for kind, field in slots:
        offsets[(kind, field["name"])] = (cursor, width(field))
        cursor += width(field)
    return offsets


def parse_output(path: Path, spec: dict, slots, return_code: int):
    offsets = output_layout_offsets(slots)
    outputs = {name: [] for name, config in spec["outputs"].items() if config.get("compare", True)}
    events = {"screen": []}
    state = {}
    for line in path.read_text(encoding="ascii").splitlines():
        tag = line[:16].strip()
        if tag in outputs:
            row = {}
            for field in spec["outputs"][tag]["fields"]:
                start, size = offsets[(tag, field["name"])]
                row[field["name"]] = decode_field(line[start : start + size], field)
            outputs[tag].append(row)
        elif tag == "EVENT-SCREEN":
            start, size = offsets[("event", "eventValue")]
            events["screen"].append(
                decode_field(line[start : start + size], {"name": "eventValue", "type": "text", "length": 48})
            )
        elif tag == "STATE":
            for field in spec.get("state", []):
                start, size = offsets[("state", field["name"])]
                state[field["name"]] = decode_field(line[start : start + size], field)
    trace = {"events": events, "state": state, "returnCode": return_code}
    return outputs, trace


def decode_fixed_records(data: bytes, record_width: int) -> list[str]:
    if record_width < 1 or len(data) % record_width:
        raise ValueError(f"invalid fixed-record data length {len(data)} for width {record_width}")
    return [
        data[offset : offset + record_width].decode("latin-1").rstrip(" ")
        for offset in range(0, len(data), record_width)
    ]


def value_at_json_path(value: dict, path: str):
    return get_path(value, path)


def check_expect_legacy(golden: dict, fixture: dict, path: Path):
    for json_path, expected in fixture.get("expectLegacy", {}).items():
        try:
            actual = value_at_json_path(golden, json_path)
        except (KeyError, IndexError, TypeError) as error:
            raise ValueError(f"{path}: expectLegacy path {json_path} is absent") from error
        if isinstance(actual, Decimal) or isinstance(expected, Decimal):
            matches = Decimal(str(actual)) == Decimal(str(expected))
        else:
            matches = actual == expected
        if not matches:
            raise ValueError(
                f"{path}: expectLegacy {json_path}={expected!r}, COBOL produced {actual!r}"
            )


def run_fixture(spec: dict, fixture_path: Path, keep_build=False):
    fixture = load_json(fixture_path)
    fixture["_path"] = str(fixture_path)
    build_dir = PARITY / "build" / spec["program"] / fixture_path.stem
    build_dir.mkdir(parents=True, exist_ok=True)
    generated, _, _, slots = build_driver(spec)
    source_path = build_dir / f"{spec['program']}.cbl"
    binary_path = build_dir / spec["program"]
    input_path = build_dir / "input.dat"
    output_path = build_dir / "output.dat"
    report_path = build_dir / "premium-report.dat"
    source_path.write_text(generated, encoding="utf-8")
    write_input(input_path, fixture, spec)
    compile_result = subprocess.run(
        [
            "cobc",
            "-x",
            "-I",
            str(ROOT / "cobol" / "copybooks"),
            "-fbinary-size=2-4-8",
            "-fbinary-byteorder=big-endian",
            "-o",
            str(binary_path),
            str(source_path),
        ],
        cwd=ROOT,
        text=True,
        capture_output=True,
    )
    if compile_result.returncode:
        raise RuntimeError(
            f"{spec['program']} compile failed:\n{compile_result.stdout}{compile_result.stderr}"
        )
    env = dict(os.environ, PX_INPUT=str(input_path), PX_OUTPUT=str(output_path))
    if spec["program"] == "PREMBAT":
        env["PX_REPORT"] = str(report_path)
    run_result = subprocess.run(
        [str(binary_path)], cwd=ROOT, env=env, text=True, capture_output=True
    )
    outputs, trace = parse_output(output_path, spec, slots, run_result.returncode)
    if spec["program"] == "PREMBAT":
        trace["events"]["report"] = (
            decode_fixed_records(report_path.read_bytes(), 132)
            if report_path.exists()
            else []
        )
    golden = {
        "program": spec["program"],
        "fixture": fixture_path.stem,
        "description": fixture["description"],
        "outputs": outputs,
        "trace": trace,
    }
    check_expect_legacy(golden, fixture, fixture_path)
    return golden


def golden(program: str | None):
    programs = [program] if program else [
        path.parent.name for path in sorted((PARITY / "programs").glob("*/spec.json"))
    ]
    for current in programs:
        spec_path = PARITY / "programs" / current / "spec.json"
        spec = load_json(spec_path)
        for fixture_path in fixture_paths(current):
            result = run_fixture(spec, fixture_path)
            output_path = PARITY / "golden" / current / f"{fixture_path.stem}.json"
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(dump_json(result), encoding="utf-8")
    return 0


def lock():
    for spec_path in sorted((PARITY / "programs").glob("*/spec.json")):
        program = spec_path.parent.name
        (spec_path.parent / "source.lock").write_text(source_lock(program), encoding="utf-8")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("check")
    golden_parser = subparsers.add_parser("golden")
    golden_parser.add_argument("--program")
    subparsers.add_parser("lock")
    args = parser.parse_args(argv)
    if args.command == "check":
        failures = check_fixtures()
        if failures:
            print("\n".join(failures), file=sys.stderr)
            return 1
        print("Parity fixture, golden, and source-lock checks passed.")
        return 0
    if args.command == "golden":
        return golden(args.program)
    return lock()


if __name__ == "__main__":
    raise SystemExit(main())
