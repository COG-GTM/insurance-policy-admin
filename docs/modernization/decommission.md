# Legacy source decommission

Do not begin until cutover and two-week hypercare are clean, the rollback window is closed
without a flag-back, every route is `retired` through a reviewed route-flags PR, facade
traffic has been zero for 30 days including month-end, queue preconditions in
[`queue-decommission.md`](queue-decommission.md) are met, and all owner
roles sign off.

Create the `pas-legacy-final` tag on the final pre-decommission commit. Execute
`decommission-legacy-trees.sh` only in a detached worktree or approved target clone; never
apply it to the active checkout. Review the planned removals and compatibility allow-list,
then create a PR with the resulting changes and decommission-rehearsal evidence.

## Mainframe and data retirement

- CICS systems programming disables PNEW, PRWL, PEND, PQRY, and PUWR with
  `CEMT SET TRANSACTION(PNEW) DISABLED` (repeat for each transaction) after confirming the
  CSD definitions. Remove RDO transactions/programs with `CEDA DELETE` only after the CSD
  artifact (M-03) and group are identified; the exact group is TBD. Retire DB2CONN/DB2ENTRY
  and plans afterward.
- Scheduling (CA-7) holds PAS0050/PAS0100/PAS0200 before deleting job definitions, triggers,
  and schedules. Commands are TBD pending the scheduling team.
- MQ admin and records management follow the queue-decommission inventory; retain definitions
  until queue-depth and put-count remain zero for 30 consecutive days.
- DB2 DBA archives ACMEINS with a final unload and image copy, confirms retention and legal
  holds, and completes an archive restore test before dropping objects (ADR 0001 open
  question 8). Retire DB2 users and plans only after linked-server removal is confirmed;
  coordinate linked-server retirement with
  [`broker-portal-migration.md`](broker-portal-migration.md).
- Capacity planning and Finance compare SMF type 30/70–72 and RMF CPU reports for at least
  one full month including month-end, normalized by workload volume. Confirmation is pending.

| Activity | Owner role | Status |
| --- | --- | --- |
| CICS definitions and transactions | CICS systems programming | Pending |
| CA-7 schedules | Scheduling (CA-7) team | Pending |
| Queues and consumers | MQ admin / Records management/compliance | Pending |
| DB2 archive and access retirement | DB2 DBA | Pending |
| MIPS evidence | Capacity planning / Finance | Pending |
| Repository trees | PAS modernization on-call (TBD) | Pending |
