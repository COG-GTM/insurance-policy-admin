# Mainframe PAS decommission (AB-530)

Ordered steps to switch off the legacy PAS components once [the cutover](runbooks/cutover.md) has
held through hypercare and the rollback window. **Nothing below has been done.** Every step is
a production action owned by a person and stays **Pending** until that person records it in the
change record.

Start condition (all of them): cutover done; two weeks with no Sev1/Sev2 (Pending: Ops);
rollback window closed with no rollback (Pending: Business owner); every capability's flag can go
`retired` (`python3 scripts/cutover/route_flags.py set --production retired --all` passes);
facade traffic-to-zero criterion met ([facade-retirement.md §4](facade-retirement.md#4-traffic-to-zero-criterion)).

After D-03 the rollback in [rollback.md](runbooks/rollback.md) is no longer possible.

| # | Step | Details | Owner | Status |
|---|---|---|---|---|
| D-01 | Set route flags to `retired` | reviewed PR to `pas-platform/routing/route-flags.json`; gateway removes the legacy upstreams | Platform team | Pending: Platform team |
| D-02 | Stop reverse replication PostgreSQL → DB2 and the DB2 → pas sync for good | stop the replication product; `PAS_MIGRATION_ENABLED=false`; remove `PAS_MIGRATION_SOURCE_*` secrets | Mainframe ops lead, DBA lead | Pending: Mainframe ops lead |
| D-03 | **Disable the CICS transactions** PNEW, PRWL, PEND, PQRY, PUWR, then remove them and programs POLNEW, POLRNW, POLEND, POLQRY, UNDWRT (and BMS mapsets) from the CSD | CEMT SET TRANSACTION(…) DISABLED, then CSD DELETE in the change; keep the load modules in the archive (D-07) | Mainframe ops lead | Pending: Mainframe ops lead |
| D-04 | **Remove PAS jobs from CA-7** | PAS0050 (PREMIUM-BATCH/PREMBAT), PAS0100 (DAILY-EXTRACT/POLEXT), PAS0200 (MONTHLY-EXPOSURE/MONEXP), with their triggers, predecessors and successors (not in the repo, [OQ-16](open-questions.md#oq-16), [M-05](missing-artifacts.md#m-05)) | Batch operations | Pending: Batch operations |
| D-05 | Remove the FTP steps and destinations | FTP from `DAILYEXT`/`MONEXP` to `CLAIMSRV.ACME.LOCAL` / `ACTUARIAL.ACME.LOCAL`; close the FTP firewall rules; Claims and Actuarial confirm they only read the SFTP feed | Mainframe ops lead, Network team, Claims, Actuarial | Pending: Network team |
| D-06 | **Decommission queues** | `ACME.PAS.UNDERWRITING.REQUEST` ([I-04](integrations.md#i-04)), `ACME.PAS.UNDERWRITING.DECISION` ([I-05](integrations.md#i-05)), TS queue `ENDORSEMENT-AUDIT` ([I-07](integrations.md#i-07); replaced by `pas.policy_audit`, AB-526), the TDQ→MQ bridge definitions and the FileNet MQ subscription ([I-06](integrations.md#i-06)). This list comes from AB-517 discovery; **the final list is pending AB-528 merge** (AB-528 owns the replacement and the queue inventory) | MQ admin, Mainframe ops lead, FileNet/ECM team | Pending: AB-528 merge, then MQ admin |
| D-07 | **Archive DB2 data per the retention policy** | Unload ACMEINS (POLICIES, COVERAGES, PREMIUMS, ENDORSEMENTS, UNDERWRITING_DECISIONS, POLICY_HOLDERS, TERRITORY_FACTORS, POLICY_SEQ value) plus DDL, plans and load modules, to the approved archive; checksums recorded; reconcile the unload against the final reconciliation report (row counts and control totals). Retention period, archive store and PII handling (POLICY_HOLDERS holds DOB/SSN/tax ID in clear in DB2): **Pending: Data governance** | DBA lead, Data governance | Pending: Data governance |
| D-08 | Drop the ACMEINS database / tablespaces and the DB2 plans (PREMPLAN etc.) after the archive is verified | DB2 change | DBA lead | Pending: DBA lead |
| D-09 | **Revoke facade credentials and retire the facade** | stop `java-facade` containers; revoke its DB2 user and JCC connection secrets; remove its gateway route, DNS name, network policy and monitoring; revoke the gateway service tokens that only existed for the facade; `COMPAT_AUTH_HEADER` repo secret no longer needed | Security, Facade owner (T. Nguyen), Platform team | Pending: Security |
| D-10 | **Remove `cobol/`, `jcl/`, `java-facade/` from main** | `CUTOVER_SIGNOFF_REF=<change id> scripts/decommission/remove-legacy-trees.sh`, open a PR, CI must be green. The script keeps the copybooks (`parity/contracts/copybooks`) and the ACMEINS DDL/seed (`parity/fixtures/acmeins`), removes the COBOL parity harness, the extract oracle and `compat/api/run_facade.sh`, rewires `make compat` / `make parity` and the CI jobs, and allow-lists the compat findings with AB-530. Tag the last commit with the legacy trees first (`pas-legacy-final`). Proven safe on every PR by CI job `decommission / legacy-removal dry run` (`make decommission-dry-run`) | PAS Platform tech lead | Pending: cutover sign-off |
| D-11 | Remove transitional platform pieces | Flyway migration **V10** (pas-policy-service): drop schema `pas_shadow` and the migration control tables (ADR 0003); delete `batch.migration` jobs and the shadow premium writer; delete the docs that only describe the transition | PAS Platform | Pending: PAS Platform (after D-02) |
| D-12 | Update the docs for the decommissioned state | README "Legacy" section, `parity/README.md` (golden capture no longer reproducible; source in `pas-legacy-final`), `compat/README.md` (no facade API check; `PolicyInquiryApiIT` holds the contract), mark the quirk register historical | PAS Platform | Pending: PAS Platform |
| D-13 | **Confirm the MIPS saving** | compare the mainframe MIPS/MSU and licence charges for PAS (CICS region, DB2 subsystem share, batch) for the month before cutover vs the month after D-08; report to FinOps. Baseline and method: **Pending: FinOps + Mainframe team** (no figures exist in this repo, ADR 0001 cost table is TBD) | FinOps, Mainframe team | Pending: FinOps |
| D-14 | Ops handover accepted and docs signed off | [operations-handover.md](runbooks/operations-handover.md) | Ops, Business owner, ARB | Pending: Ops |

## What stays

- `compat/api/snapshots/policies-v1.json` is still the inquiry API contract, asserted by
  `PolicyInquiryApiIT` in `pas-policy-service`.
- `parity/programs/*/golden.csv` and `parity/known-differences.json` stay as the record of COBOL
  behaviour the Java code is tested against (`make parity-java`).
- `parity/extracts/golden/` stays as the byte-level contract of the Claims/Actuarial feeds
  (`make parity-extracts`).
- `sql/` (DB2 DDL) stays until D-08 as a reference for the archive.

## Acceptance criteria status

| Criterion | Status |
|---|---|
| Cutover done with no Sev1/Sev2 in the first 2 weeks | Pending: Ops |
| Mainframe PAS components switched off (D-03 … D-08) | Pending: Mainframe ops lead, Batch operations, DBA lead |
| MIPS saving confirmed (D-13) | Pending: FinOps |
| Docs updated and signed off (D-12, D-14) | Docs updated in AB-530; sign-off Pending: Ops, Business owner, ARB |
