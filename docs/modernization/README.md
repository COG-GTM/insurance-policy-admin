# PAS modernization: discovery (AB-517)

These documents describe what the legacy Policy Administration System actually does today. Epic AB-516
uses them to migrate PAS off COBOL/CICS/DB2. They are derived from the source in `cobol/`, `jcl/`,
`sql/ddl/` and `java-facade/` on the integration branch, and every claim carries a `path:line` reference.

| Document | Contents | IDs |
|----------|----------|-----|
| [behaviour-inventory.md](behaviour-inventory.md) | Per CICS transaction (PNEW/POLNEW, PRWL/POLRNW, PEND/POLEND, PQRY/POLQRY, PUWR/UNDWRT) and batch job (PREMIUM-BATCH/PREMBAT, DAILY-EXTRACT, MONTHLY-EXPOSURE): inputs, outputs, DB2 reads and writes, messages and files, paragraph-level rules; table CRUD matrix; copybook record lengths and JCL LRECLs | n/a |
| [integrations.md](integrations.md) | Every integration point and the owner of the other end | `I-NN` |
| [quirk-register.md](quirk-register.md) | Quirks and defects with location, impact, owner, Preserve / Fix / Fix (flagged) decision and implementing ticket | `Q-NN` |
| [missing-artifacts.md](missing-artifacts.md) | Things referenced but not in this repo (CLAIMS DDL, BMS maps, CSD, TDQ/MQ, CA-7, RACF, …) | `M-NN` |
| [open-questions.md](open-questions.md) | Questions for the mainframe team, product owner and system owners | `OQ-NN` |
| [migration runbook](../runbooks/migration.md) | AB-529 bulk/delta/reverse sync, reconciliation, parallel-run triage and rollback rehearsal | `AB-529` |
| [cutover runbook](../runbooks/cutover.md) | AB-530 route cutover, go/no-go, hypercare and rollback | `AB-530` |
| [decommission runbook](decommission.md) | Guarded retirement of source trees and mainframe resources | `AB-530` |

How the IDs are used across the epic:

- Code, tests and parity "known differences" that preserve or fix a legacy behaviour cite its `Q-NN`.
- Q-ids are stable. A quirk that turns out to be wrong is marked withdrawn, not renumbered.
- New quirks found by later tickets are appended with the next free number.

See the [AB-530 cutover, rollback, and decommission overview](cutover.md).

## Review & sign-off

The inventory and quirk decisions are **proposed** until both parties below sign off. A sign-off
covers the behaviour description, the integration owners, and every Preserve/Fix decision in the
quirk register. Decisions they change are updated in place and noted in the register.

| Reviewer | Scope | Status | Date | Notes |
|----------|-------|--------|------|-------|
| Mainframe team (mainframe-ops@acme.com) | Behaviour inventory, integrations, missing artifacts, technical quirks (Q-05, Q-15, Q-26, Q-28, Q-29, Q-34–Q-36, Q-40, Q-41) | Pending | | |
| PAS product owner | Business quirks and every preserve/fix decision; open questions routed to business owners | Pending | | |
