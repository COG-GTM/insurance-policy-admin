# PAS modernization docs (AB-516)

Discovery outputs from AB-517. Read them in this order:

| Document | What it answers |
|----------|-----------------|
| [inventory.md](inventory.md) | What each CICS transaction, batch job and the REST facade does: triggers, inputs, outputs, DB2 reads/writes, BMS maps |
| [integrations.md](integrations.md) | Every integration point, its protocol and layout, and who owns the other end |
| [quirk-register.md](quirk-register.md) | Each quirk or defect with an owner and a preserve/fix decision (stable `#q-nn` anchors used by `parity/known-differences.json`) |
| [missing-artifacts.md](missing-artifacts.md) | Things referenced by the code that are not in this repo |
| [open-questions.md](open-questions.md) | Unanswered questions written as proposed follow-up tickets |
| [extract-layouts.md](extract-layouts.md) | AB-527 byte layouts of the Claims/Actuarial extract files |
| [extracts-runbook.md](extracts-runbook.md) | AB-527 jobs, schedules, SFTP delivery, alerting, rerun and cut-over |
| [data-migration.md](data-migration.md) | AB-529 bulk load, delta sync vs CDC, reconciliation, rollback |
| [parallel-run.md](parallel-run.md) | AB-529 daily parallel-run diff and difference triage |
| [runbooks/cutover.md](runbooks/cutover.md) | AB-530 cutover: freeze, final sync, routing switch per capability, smoke tests, go/no-go, comms |
| [runbooks/rollback.md](runbooks/rollback.md) | AB-530 path back to CICS/DB2 per capability |
| [runbooks/rehearsal-results-local.md](runbooks/rehearsal-results-local.md) | AB-530 local cutover and rollback rehearsal results |
| [runbooks/operations-handover.md](runbooks/operations-handover.md) | AB-530 on-call, dashboards, alerts, batch schedule, key rotation |
| [decommission.md](decommission.md) | AB-530 ordered mainframe decommission steps and owners |

Review status: Pending: Mainframe team lead. Pending: Product Owner (PAS). All quirk
decisions are **Proposed — pending PO sign-off**.
