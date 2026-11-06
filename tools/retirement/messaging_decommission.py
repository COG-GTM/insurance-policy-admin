#!/usr/bin/env python3
"""Gated decommission of the TD/MQ queues, the MQ bridge and the Broker Portal DB2 link (AB-528).

Dry run by default: lists the queue and link definitions to decommission, the gates and the steps
from docs/pas/messaging-decommission.json. --check validates the plan, that every listed definition
is still where the plan says it is, and that the MQ sink configs match the bridge topics. Every step
is outside this repository; --execute refuses until all gates are approved and then only prints
them. Nothing here changes the legacy trees.
"""
import argparse
import glob
import json
import os
import re
import sys

import facade_retirement as fr

ROOT = fr.ROOT
PLAN = os.path.join(ROOT, "docs", "pas", "messaging-decommission.json")
SINKS = os.path.join(ROOT, "pas", "deploy", "messaging")
BRIDGE_PROPERTIES = os.path.join(
    ROOT, "pas", "pas-policy-service", "src", "main", "java", "com", "acme", "insurance", "pas",
    "policyservice", "bridge", "BridgeProperties.java")
ITEM_KEYS = ("id", "kind", "name", "defined_in", "evidence", "replaced_by", "owner")


def validate(plan, root=ROOT):
    errors = fr.validate(plan)
    items = plan.get("decommission")
    if not items:
        return errors + ["missing 'decommission' list"]
    ids = [i.get("id") for i in items]
    if len(ids) != len(set(ids)):
        errors.append("duplicate decommission ids")
    for item in items:
        missing = [k for k in ITEM_KEYS if k not in item]
        if missing:
            errors.append(f"{item.get('id')}: missing {missing}")
            continue
        if not item["defined_in"]:
            continue
        path = os.path.join(root, item["defined_in"])
        if not os.path.isfile(path):
            errors.append(f"{item['id']}: {item['defined_in']} not found")
        elif item["evidence"] not in open(path, encoding="utf-8", errors="replace").read():
            errors.append(f"{item['id']}: '{item['evidence']}' not in {item['defined_in']}")
    for step in plan.get("steps", []):
        if step.get("command"):
            errors.append(f"{step['id']}: decommission steps are manual, no command allowed")
    return errors


def bridge_topics(path=BRIDGE_PROPERTIES):
    source = open(path, encoding="utf-8").read()
    # Only MQ-bound topics need a sink; pas.bridge.retry is Kafka-internal (DLT replay).
    return set(re.findall(r'@DefaultValue\("(pas\.bridge\.(?:mq|filenet)\.[a-z.-]+)"\)', source))


def check_sinks(directory=SINKS, topics=None):
    topics = bridge_topics() if topics is None else topics
    errors, seen = [], set()
    for path in sorted(glob.glob(os.path.join(directory, "mq-sink-*.json"))):
        name = os.path.basename(path)
        config = json.load(open(path, encoding="utf-8"))["config"]
        seen.add(config.get("topics"))
        if config.get("topics") not in topics:
            errors.append(f"{name}: topic {config.get('topics')} is not a bridge topic")
        if config.get("value.converter") != "org.apache.kafka.connect.converters.ByteArrayConverter":
            errors.append(f"{name}: value.converter must be ByteArrayConverter (125-byte record)")
        if config.get("mq.message.body.jms") != "false":
            errors.append(f"{name}: mq.message.body.jms must be false")
        for key, value in config.items():
            if key in ("mq.password", "mq.user.name") and not str(value).startswith("${"):
                errors.append(f"{name}: {key} must come from a config provider")
    for topic in sorted(topics - seen):
        errors.append(f"no MQ sink config for bridge topic {topic}")
    return errors


def print_inventory(plan, out=None):
    out = out or sys.stdout
    print("Definitions to decommission:", file=out)
    for i in plan["decommission"]:
        where = i["defined_in"] or "outside this repository"
        print(f"  {i['id']} {i['kind']} {i['name']} ({where}) -> {i['replaced_by']} [owner: {i['owner']}]", file=out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--plan", default=PLAN)
    ap.add_argument("--check", action="store_true", help="validate the plan and the sink configs")
    ap.add_argument("--execute", action="store_true", help="print the manual steps once every gate is approved")
    args = ap.parse_args(argv)
    plan = fr.load(args.plan)
    errors = validate(plan) + check_sinks()
    if errors:
        for e in errors:
            print(f"messaging-decommission: {e}", file=sys.stderr)
        return 1
    if args.check:
        print(f"messaging-decommission: plan valid, {len(plan['decommission'])} definitions listed, "
              f"{len(fr.open_gates(plan))} of {len(plan['gates'])} gates open")
        return 0
    print_inventory(plan)
    fr.print_plan(plan)
    if not args.execute:
        print("messaging-decommission: dry run, nothing changed")
        return 0
    reason = fr.refusal(plan, fr.current_branch())
    if reason:
        print(f"messaging-decommission: {reason}", file=sys.stderr)
        return 2
    print("messaging-decommission: all gates approved; run steps S1-S6 with their owners")
    return 0


if __name__ == "__main__":
    sys.exit(main())
