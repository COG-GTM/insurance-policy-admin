#!/usr/bin/env python3
"""Generate the AB-527 extract parity database snapshots (fixtures/<run>/*.csv).

Deterministic: each run seeds its own random.Random from the run id, then adds a fixed set of
edge-case rows (selection-window boundaries, every status, NULLs, sign/size extremes, EBCDIC vs
ASCII collation traps, non-ASCII text). CSV format matches PostgreSQL COPY (FORMAT csv, HEADER,
NULL '\\N').
"""
import csv
import datetime as dt
import json
import random
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
NULL = "\\N"

POLICY_COLUMNS = [
    "policy_number", "policy_type", "policy_status", "effective_date", "expiry_date",
    "policyholder_id", "agent_code", "branch_code", "total_premium", "deductible",
    "coverage_limit", "inception_date", "renewal_count", "uw_status", "risk_score",
    "web_indicator", "api_flag", "last_updated", "updated_by",
]
COVERAGE_COLUMNS = [
    "policy_number", "sequence_num", "coverage_type", "description", "coverage_limit",
    "deductible", "premium", "effective_date", "expiry_date", "status", "coinsurance_pct",
    "rating_territory", "class_code",
]
POLICY_TYPES = ["AUT", "HOM", "COM", "LIF", "HLT"]
COVERAGE_TYPES = ["PROP", "LIAB", "AUTP", "AUTL", "WKCP", "UMBR", "CYBR", "TERR"]
BRANCHES = ["NY01", "0100", "ny02", "NJ1", "CT07", "9000", None]
TERRITORIES = ["T00101", "T00102", "001NYC", "nyc001", None]
CLASSES = ["C1000", "C2000", "70100", None]
STATUSES = ["AC", "AC", "AC", "PN", "CN", "EX", "LP"]
DESCRIPTIONS = [
    "Dwelling", "General liability, per occurrence", "Café contents (fire, theft)",
    "Collision", None, "X" * 40, "Umbrella excess   ", "",
]


def money(rng, upper, allow_null=True, allow_negative=False):
    if allow_null and rng.random() < 0.08:
        return NULL
    cents = rng.randint(0, int(upper * 100))
    if allow_negative and rng.random() < 0.15:
        cents = -cents
    return str(Decimal(cents) / 100)


def ts(moment):
    return moment.strftime("%Y-%m-%d %H:%M:%S.%f")


def policy(number, rng, run_date, *, status=None, last_updated=None, **overrides):
    eff = run_date - dt.timedelta(days=rng.randint(0, 400))
    row = {
        "policy_number": number,
        "policy_type": rng.choice(POLICY_TYPES),
        "policy_status": status or rng.choice(STATUSES),
        "effective_date": eff.isoformat(),
        "expiry_date": (eff + dt.timedelta(days=365)).isoformat(),
        "policyholder_id": f"C{rng.randint(0, 999999999):09d}",
        "agent_code": rng.choice([f"A{rng.randint(0, 99999):05d}", NULL]),
        "branch_code": rng.choice(BRANCHES) or NULL,
        "total_premium": money(rng, 25000, allow_negative=True),
        "deductible": money(rng, 5000),
        "coverage_limit": money(rng, 5000000),
        "inception_date": rng.choice([eff.isoformat(), NULL]),
        "renewal_count": str(rng.randint(0, 5)),
        "uw_status": rng.choice(["AP", "PN", "DC", "RF", NULL]),
        "risk_score": rng.choice([str(rng.randint(0, 999)), NULL]),
        "web_indicator": "N",
        "api_flag": "N",
        "last_updated": ts(last_updated or (dt.datetime.combine(run_date, dt.time())
                                            - dt.timedelta(minutes=rng.randint(0, 4 * 1440)))),
        "updated_by": "PARITY",
    }
    row.update(overrides)
    return row


def coverage(number, seq, rng, pol, **overrides):
    row = {
        "policy_number": number,
        "sequence_num": str(seq),
        "coverage_type": rng.choice(COVERAGE_TYPES),
        "description": NULL if (d := rng.choice(DESCRIPTIONS)) is None else d,
        "coverage_limit": money(rng, 2000000),
        "deductible": money(rng, 5000),
        "premium": money(rng, 9000, allow_negative=True),
        "effective_date": pol["effective_date"],
        "expiry_date": pol["expiry_date"],
        "status": rng.choice(["AC", "AC", "AC", "CN", "PN"]),
        "coinsurance_pct": "100",
        "rating_territory": rng.choice(TERRITORIES) or NULL,
        "class_code": rng.choice(CLASSES) or NULL,
    }
    row.update(overrides)
    return row


def snapshot(run_id, run_date):
    rng = random.Random(f"ab527-{run_id}")
    midnight_prev = dt.datetime.combine(run_date - dt.timedelta(days=1), dt.time())
    policies = []
    # Generic book.
    for i in range(30):
        policies.append(policy(f"POL{100000 + i * 7 + run_date.toordinal() % 5:09d}", rng, run_date))
    # Edge cases (fixed per run, relative to the business date).
    edge = [
        policy("POL000000001", rng, run_date, status="AC", last_updated=midnight_prev),
        policy("POL000000002", rng, run_date, status="AC",
               last_updated=midnight_prev - dt.timedelta(microseconds=1)),
        policy("POL000000003", rng, run_date, status="PN",
               last_updated=midnight_prev + dt.timedelta(days=1, hours=22, minutes=59)),
        policy("POL000000004", rng, run_date, status="AC",
               last_updated=midnight_prev + dt.timedelta(days=3)),
        policy("POL000000005", rng, run_date, status="CN", last_updated=midnight_prev),
        policy("POL000000006", rng, run_date, status="EX", last_updated=midnight_prev),
        policy("POL000000007", rng, run_date, status="LP", last_updated=midnight_prev),
        policy("POL000000008", rng, run_date, status="AC", last_updated=midnight_prev,
               agent_code=NULL, branch_code=NULL, total_premium=NULL, deductible=NULL,
               coverage_limit=NULL, uw_status=NULL, risk_score=NULL),
        policy("POL000000009", rng, run_date, status="AC", last_updated=midnight_prev,
               total_premium="-999999999.99", deductible="9999999.99",
               coverage_limit="99999999999.99", risk_score="-1"),
        policy("POL000000010", rng, run_date, status="PN", last_updated=midnight_prev,
               total_premium="0.00", deductible="0.00", coverage_limit="0.00", risk_score="0"),
        # Collation traps: EBCDIC sorts lower case < upper case < digits; ASCII the reverse.
        policy("pol000000011", rng, run_date, status="AC", last_updated=midnight_prev),
        policy("POLA00000012", rng, run_date, status="AC", last_updated=midnight_prev),
        policy("POL-00000013", rng, run_date, status="AC", last_updated=midnight_prev),
        policy("POL00000014", rng, run_date, status="AC", last_updated=midnight_prev,
               branch_code="0100"),
        policy("POL000000015", rng, run_date, status="AC", last_updated=midnight_prev,
               branch_code="ny02", policy_type="AUT"),
        policy("POL000000016", rng, run_date, status="AC", last_updated=midnight_prev,
               branch_code="NY01", policy_type="AUT"),
    ]
    policies.extend(edge)
    coverages = []
    for pol in policies:
        for seq in range(1, rng.randint(1, 4) + 1):
            coverages.append(coverage(pol["policy_number"], seq, rng, pol))
    # Coverage edge cases on fixed policies.
    p8 = next(p for p in policies if p["policy_number"] == "POL000000008")
    coverages.append(coverage("POL000000008", 50, rng, p8, status="AC", description=NULL,
                              coverage_limit=NULL, deductible=NULL, premium=NULL,
                              rating_territory=NULL, class_code=NULL))
    coverages.append(coverage("POL000000008", 51, rng, p8, status="AC", coverage_type="LIAB",
                              coverage_limit="99999999999.99", deductible="-9999999.99",
                              premium="-999999999.99", description="Café, \"quoted\" text"))
    coverages.append(coverage("POL000000008", 400, rng, p8, status="AC",
                              coverage_type="CYBR", rating_territory="nyc001",
                              class_code="70100"))
    return policies, coverages


def write(path, columns, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=columns, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def main():
    runs = json.loads((HERE / "runs.json").read_text(encoding="utf-8"))["runs"]
    for run in runs:
        run_date = dt.date.fromisoformat(run["businessDate"])
        policies, coverages = snapshot(run["id"], run_date)
        write(HERE / "fixtures" / run["id"] / "policies.csv", POLICY_COLUMNS, policies)
        write(HERE / "fixtures" / run["id"] / "coverages.csv", COVERAGE_COLUMNS, coverages)
        print(f"{run['id']}: {len(policies)} policies, {len(coverages)} coverages")


if __name__ == "__main__":
    main()
