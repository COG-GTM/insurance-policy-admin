# PAS rollback runbook

## Authority and objectives

The Cutover lead or Incident commander may declare rollback. Target objectives are RPO 0
and RTO at most four hours (proposed; business confirmation pending). Preserve all
watermarks and reconciliation reports.

## Execution

Run `rollback.sh --execute --run-id <change-id>`. Add `--include-reads` for a full rollback;
without it, reads remain on the modern service.

| Step | Action |
| --- | --- |
| r01 | Route all write and document-event capabilities to legacy together |
| r02 | Drain reverse sync and confirm reverse lag is zero |
| r03 | Run reconciliation and require `CLEAN` |
| r04 | Apply roles, explicit rollback revoke, then pre-cutover grants |
| r05 | Optionally return broker read, policy inquiry, and batch reads to legacy |
| r06 | Disable reverse sync and resume forward delta |
| r07 | Unfreeze legacy writes and schedules |
| r08 | Report RPO/RTO and preserve decision evidence |

Check that writes on legacy routes return the expected unavailable response during
verification. DB2 is the system of record after rollback. Continue delta sync, preserve
watermarks, and communicate status by role/channel (TBD). Rehearse with
`make cutover-rehearsal` before production use.

Reconciliation compares underwriting decisions using the same latest-per-key projection as
reverse sync: the greatest `decision_id` for each `(policy_number, decision_date)` across
source systems. The projection follows OQ-25; whether collapsing same-day decisions is
acceptable remains open.

Reconciliation compares underwriting decisions using the same latest-per-key projection as
reverse sync: the greatest `decision_id` for each `(policy_number, decision_date)` across
source systems. The projection follows OQ-25; whether collapsing same-day decisions is
acceptable remains open.

Reconciliation compares underwriting decisions using the same latest-per-key projection as
reverse sync: the greatest `decision_id` for each `(policy_number, decision_date)` across
source systems. The projection follows OQ-25; whether collapsing same-day decisions is
acceptable remains open.
