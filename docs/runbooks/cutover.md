# PAS cutover runbook

## Preconditions

- ARB approval is complete (ARB ticket: TBD) and every capability meets D9 move criteria
  with evidence links and owner sign-off.
- The AB-529 parallel-run cycle is clean; no present `UNTRIAGED` or `BUG` differences remain.
- Latest reconciliation is `CLEAN`; rollback and smoke checks have passed in rehearsal
  (`make cutover-rehearsal`, `make rollback-rehearsal`).
- Business, Security, Capability owners, Cutover lead, and Mainframe operations approve.
- Flyway c02 check passes. On the integration branch, pas V80–V89 must be merged before
  V50/V60–V61. Fresh databases are unaffected; a pre-existing environment migrated before
  those merges may show versions as pending/ignored. Keep Flyway out-of-order disabled.
  Resolve via a reviewed fix-forward migration or baseline decision; do not edit history.
- Freeze window: proposed weekend low-traffic, after month-end premium and PAS0200 cycle;
  exact window TBD. Confirm CA-7 hold and CICS write freeze (PNEW/PRWL/PEND/PUWR); PQRY
  remains available until policy inquiry moves.

## Execution sequence

Run `cutover.sh --execute --run-id <change-id>` with the reviewed production adapters.
The script logs UTC timestamps, step outcomes, and durations; failed steps stop execution.
Re-use the run ID to resume completed steps.

| Step | Action |
| --- | --- |
| c01 | Verify ARB ticket, all capability sign-offs, D9 evidence, and route flags |
| c02 | Compare `pas` and `pas_batch` Flyway histories to repository migrations |
| c03 | Verify the latest parallel-run cycle has no untriaged or BUG differences |
| c04 | Freeze legacy writes and hold CA-7 schedules |
| c05 | Drain forward delta through the freeze timestamp and pause delta |
| c06 | Run reconciliation; require `CLEAN` and retain report location |
| c07 | Apply `roles.sql` then `post-cutover.sql`; verify service/sync grants |
| c08 | Move policy inquiry, broker portal read, daily extract, and monthly exposure—in that order |
| c09 | Move new business, underwriting, renewal, endorsement, document events, and premium batch together |
| c10 | Enable reverse sync and run one non-draining pass |
| c11 | Run authenticated readiness, inquiry, lifecycle, outbox, and optional extract checks |
| c12 | Write a proposed all-modern route-flags JSON; update the gateway audit file by PR |

Reads are ordered with policy inquiry before broker-portal-read. Do not split the c09 write
route application. The rollout is GO only when every check passes and the Cutover lead
records the decision; otherwise stop and use [rollback](rollback.md).

## Go/no-go and communications

| Criterion | Owner role | Evidence | Decision |
| --- | --- | --- | --- |
| ARB and capability approvals | Business owner / Capability owner | ARB and D9 evidence links | Pending |
| Flyway histories match | PAS modernization on-call (TBD) | c02 output | Pending |
| Parallel run and reconciliation clean | Business owner / Cutover lead | c03 and c06 reports | Pending |
| Routes and smoke checks pass | Capability owner / Security | c08–c11 output | Pending |
| Rollback ready | Mainframe operations / Cutover lead | rehearsal and reverse lag | Pending |

Notify the change channel and incident commander at freeze, each route phase, go/no-go,
and hypercare start; channel and contacts TBD. Never include JWTs, credentials, or PII.

## Hypercare and triggers

Hypercare lasts two weeks. Run daily reconciliation and monitor reverse-sync lag daily.
Exit after 14 days without a Sev1 or Sev2. Sev1: PAS writes unavailable, data corruption/loss,
PII exposure, or incorrect premium charged at scale. Sev2: a capability is degraded or
partially unavailable, unexplained reconciliation differences appear, sync/reverse lag
exceeds threshold, or an extract is missed.

Rollback triggers: any Sev1; smoke failure; unexplained non-CLEAN reconciliation; growing
reverse-sync lag; or the go/no-go owner call. Follow [rollback.md](rollback.md).
This runbook addresses D5, D9, D10, NFRs, and OQ-24/25/26.
