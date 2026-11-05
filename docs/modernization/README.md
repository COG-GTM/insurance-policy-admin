# PAS modernization documentation

Working documents for epic **AB-516** (migrating the PAS off COBOL/CICS/DB2 to Java 21 / Spring Boot 3).

| Document | Ticket | What it is |
|---|---|---|
| [behaviour-inventory.md](behaviour-inventory.md) | AB-517 | What every CICS transaction and batch job does: inputs, outputs, DB2 tables read/written, messages and files sent, integration points with owners, and artefacts referenced but missing from the repo |
| [quirk-register.md](quirk-register.md) | AB-517 | Every quirk/defect with an owner and a preserve/fix decision (source: [quirks.json](quirks.json)) |
| [open-questions.md](open-questions.md) | AB-517 | Open questions written as ready-to-file follow-up tickets |
| [legacy-facts.json](legacy-facts.json) | AB-517 | Facts extracted from the COBOL/JCL/DDL by `tools/discovery/pas_inventory.py` (generated) |

## Keeping the inventory honest

```
make inventory                                    # check: facts current, docs cover every program/job/table, quirks valid
python3 tools/discovery/pas_inventory.py --write  # regenerate after changing quirks.json or the docs
python3 -m unittest discover -s tools/discovery   # unit tests for the scanner
```

CI (`.github/workflows/discovery-inventory.yml`) runs both on pull requests into `main` and `devin/ab-516-v3`. The check only reads `cobol/`, `jcl/`, `sql/` and `java-facade/`, which stay byte-for-byte unchanged.

## Sign-off

The acceptance criteria require review by the mainframe team and the product owner, plus an owner and decision for every quirk. Those reviews cannot happen inside this repository, so this table records them as pending.

| Review | Reviewer | Scope | Status | Date / reference |
|---|---|---|---|---|
| Behaviour inventory accuracy | Mainframe team (mainframe-ops@acme.com) | behaviour-inventory.md §2-§9, all *needs verification* quirks | pending | |
| Behaviour inventory and quirk decisions | Product Owner, Policy Administration | behaviour-inventory.md, quirk-register.md (all proposed decisions) | pending | |
| Rating quirks (Q-03, Q-05, Q-06, Q-12, Q-20, Q-26, Q-27, Q-34) | Actuarial / Pricing lead | quirk-register.md | pending | |
| Underwriting quirks (Q-14..Q-18, Q-39) | Underwriting lead | quirk-register.md | pending | |
| Regulatory items (Q-04, Q-23, Q-32) | Compliance | quirk-register.md | pending | |
| Security items (Q-32, Q-36) | Security | quirk-register.md | pending | |
| Data / schema items (Q-35, Q-37) | DBA team (db2-admin@acme.com) | quirk-register.md, §10 | pending | |
| Integration owners I-1..I-10 | Claims, Actuarial, Broker Portal, ECM, UW ops | behaviour-inventory.md §7 | pending | |
| Follow-up tickets FU-01..FU-20 filed in Jira | Programme manager | open-questions.md | pending | |
