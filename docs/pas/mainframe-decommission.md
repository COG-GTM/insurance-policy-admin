# PAS mainframe decommission (AB-530)

Switches off the PAS mainframe components after cutover and hypercare, archives the DB2 data under
the retention policy, and removes `cobol/`, `jcl/` and `java-facade/` from `main`. Plan:
[`mainframe-decommission.json`](mainframe-decommission.json); tool:
`tools/retirement/mainframe_decommission.py` (`make mainframe-decommission`).

**Nothing is decommissioned from this branch.** The tool is dry-run by default. `--execute` refuses
while any gate below is not approved and on the protected branches `main` and `devin/ab-516-v3`;
even then its only scripted step is `git rm -r -q cobol jcl java-facade` in the working tree, for a
human-reviewed PR. Every mainframe action is manual, run by its owner under a change record.

## What is switched off

| Id | Component | Replaced by | Owner |
|---|---|---|---|
| D1-D5 | CICS transactions PNEW, PRWL, PEND, PQRY, PUWR and programs POLNEW, POLRNW, POLEND, POLQRY, UNDWRT | policy, renewal, endorsement, inquiry and underwriting APIs | Mainframe team |
| D6-D8 | CA-7 jobs PAS0050, PAS0100, PAS0200 (with triggers, calendars, console alerts, FU-12) | `premiumRecalculation`, `claimsDailyExtract`, `actuarialMonthlyExposure` | Mainframe team (scheduling) |
| D9 | `ACME.PAS.LOADLIB` and the PAS CSD group | container images | Mainframe team |
| D10 | DB2 ACMEINS (tablespace PASTS01), after archive | PostgreSQL ACMEINS | DBA |
| D11 | transition jobs `migrationDeltaSync`, `migrationReverseSync`, `migrationReconciliation`, schema `acmeins_shadow`, DB2 datasource and transition account (PLT-02) | nothing | Platform SRE |
| D12 | `java-facade` deployment (AB-524 plan) | inquiry API | Facade owner |

The TD/MQ queues, Broker Portal linked server and Kafka-to-MQ bridge are decommissioned by the AB-528
plan ([messaging-decommission.md](messaging-decommission.md)); gate G5 waits for it and for AB-524.

`make mainframe-decommission` checks that every definition still exists where the plan says (a
renamed transaction or job fails the check), so the plan cannot silently drift from the repository.

## Gates

| Gate | Condition | Owner | Status |
|---|---|---|---|
| G1 | Cutover complete: `make cutover` GO, every capability `NEW` | Cutover lead | pending |
| G2 | Hypercare exit: 14 days without Sev1/Sev2 (`make cutover-hypercare`) | Service owner | pending |
| G3 | Rollback window closed with a recorded decision | Cutover lead + Product owner | pending |
| G4 | No CICS traffic on the five transactions for the agreed period (CICS statistics, SMF 110) | Mainframe team | pending |
| G5 | AB-524 and AB-528 retirement plans executed | Platform SRE | pending |
| G6 | DB2 archive verified (counts, checksums, sample restore); retention approved | DBA + Compliance | pending |
| G7 | MIPS savings confirmed (`mips.after_msu` below `mips.baseline_msu`) | Capacity planning | pending |
| G8 | Architecture docs and support runbook signed off; operations handover accepted | Service owner + ARB | pending |
| G9 | Repository dependents repointed; `make compat`, `make pas-verify` and CI pass without the trees | PAS tech lead | pending |

## Steps

| Step | Action | Scripted |
|---|---|---|
| S1 | `CEMT SET TRANSACTION(PNEW,PRWL,PEND,PQRY,PUWR) DISABLED` at cutover; after G4 `CEDA DELETE` the transactions and programs and remove the group from the region's GRPLIST | manual |
| S2 | Remove CA-7 PAS0050, PAS0100, PAS0200 after their last held cycle, with triggers, predecessors and console alerts | manual |
| S3 | Unload and archive ACMEINS (below); revoke the transition and Broker Portal grants; drop PASTS01 after G6 | manual |
| S4 | Delete the migration CronJobs and the DB2 datasource; drop `acmeins_shadow` (Flyway migration in the decommission PR) | manual |
| S5 | Scratch `ACME.PAS.LOADLIB` members and `ACME.PAS.*` datasets after the retention review | manual |
| S6 | Repoint the repository dependents (below) | manual |
| S7 | `git rm -r -q cobol jcl java-facade` | `--execute` |
| S8 | Decommission PR into `main` with the MIPS evidence and signed-off docs, merged by humans | manual |

## DB2 archive and retention

Every ACMEINS table in `sql/ddl/create-tables.sql` is unloaded after the last reverse sync (DB2
UNLOAD with catalog DDL) to the enterprise archive as read-only datasets, with row counts and SHA-256
per unload recorded, and a sample restore into a scratch DB2 subsystem proves the archive is readable.
`make mainframe-decommission` fails if a table in the DDL has no archive entry.

| Table | Retention | Notes |
|---|---|---|
| POLICIES | pending (FU-530-4) | policy master, regulatory record |
| COVERAGES | pending (FU-530-4) | |
| PREMIUMS | pending (FU-530-4) | financial record |
| ENDORSEMENTS | pending (FU-530-4) | endorsement audit (FU-09) |
| UNDERWRITING_DECISIONS | pending (FU-530-4) | includes overrides |
| POLICY_HOLDERS | pending (FU-530-4) | PII: encrypted archive, access only with Data governance approval |
| TERRITORY_FACTORS | pending (FU-530-4) | reference data |

## MIPS savings

Measured as the 4-hour rolling average MSU of the PAS CICS region and batch service classes (SMF
70/72 or the sub-capacity report), same calendar month before cutover and after decommission
(FU-530-5). Record `mips.baseline_msu` and `mips.after_msu`; the tool prints the saving and refuses
an approved G7 without one.

## Removing the legacy trees from `main`

`sql/` stays: it is the schema baseline for the compat gate and the archive restore. Before S7, the
files that read the trees are repointed; the tool lists them (`repository.dependents`) and fails when
a new file outside the trees references `cobol/`, `jcl/` or `java-facade/` without an entry. The
biggest items: freeze copybook/JCL layouts into compat baselines and retarget `compat-api` to the
`pas-policy-service` contract (AB-524 G4); move the copybooks, JCL SYSIN and facade seed that tests
read into test resources; archive the COBOL characterization harness (golden JSON kept).

## Sign-offs

| Party | Status |
|---|---|
| Mainframe team (CICS, CA-7, load libraries) | pending |
| DBA (archive and DB2 drop) | pending |
| Compliance and Data governance (retention, PII archive) | pending |
| Capacity planning / finance (MIPS savings) | pending |
| Operations (support handover) | pending |
| Security | pending |
| Product owner | pending |
| ARB | pending |
