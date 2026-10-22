# PAS integration points (AB-517)

These are all the places where PAS exchanges data with another system or person. "Owner of the other end" is
the role accountable for the consumer or producer outside PAS. Named contacts appear only where the
README names them (mainframe-ops@acme.com, db2-admin@acme.com, t.nguyen@acme.com). The
replacement column follows the AB-516 architecture baseline. Behaviour details are in
[behaviour-inventory.md](behaviour-inventory.md), and defects in [quirk-register.md](quirk-register.md).

| I-id | Integration | Direction | Transport | Other end (owner) | Replacement | Ticket |
|------|-------------|-----------|-----------|-------------------|-------------|--------|
| I-01 | Claims daily extract | PAS → Claims | DSNTEP2 + plaintext FTP | Claims Engine VB6 import (Claims system owner) | SFTP + checksum + `.done` | AB-527 |
| I-02 | Actuarial monthly exposure | PAS → Actuarial | DSNTEP2 + plaintext FTP | SAS reserving/pricing (Actuarial) | SFTP + checksum + `.done` | AB-527 |
| I-03 | Broker Portal linked server | Broker Portal → DB2 | SQL Server linked server, read-only ODBC | Broker Portal team | read API on pas-policy-service | AB-528 |
| I-04 | Underwriting referral | POLNEW → UW queue | CICS TDQ (MQ name) | Underwriting operations / MQ admin | `pas.underwriting.events.v1` via outbox | AB-525, AB-528 |
| I-05 | UW decision → FileNet documents | UNDWRT → MQ → FileNet | CICS TDQ (MQ name) → IBM MQ | ECM / FileNet team | `pas.document.requests.v1` via outbox + legacy bridge | AB-528 |
| I-06 | Claims history table | DB2 `ACMEINS.CLAIMS` → POLRNW, UNDWRT | direct SQL in the shared subsystem | Claims team | `ClaimsHistoryPort` adapter | AB-525, AB-526 |
| I-07 | Endorsement audit | POLEND → TS queue | CICS TS queue | Compliance (reader unknown) | durable audit table + policy event | AB-526, AB-528 |
| I-08 | Premium batch alert | PREMIUM-BATCH → operator | TSO SEND to user PASADMIN | Mainframe operations | job metrics + alerting | AB-523 |
| I-09 | Batch schedule | CA-7 → jobs | CA-7 PAS0050 / PAS0100 / PAS0200 | Mainframe operations (scheduling) | pas-batch scheduler / enterprise scheduler | AB-523, AB-527 |
| I-10 | REST facade | clients → DB2 | HTTP JSON → JDBC (JCC T4) | Java facade owner (t.nguyen@acme.com); "modern client systems" | pas-policy-service inquiry API | AB-524 |
| I-11 | 3270 operators | CSRs / underwriters ↔ CICS | BMS maps | PAS product owner (business users) | REST APIs + UI out of scope | AB-524–AB-526 |
| I-12 | Premium report dataset | PREMBAT → dataset | `ACME.PAS.REPORTS.PREMIUM.D&LYYMMDD` | Mainframe operations (reader unknown) | job summary table + report | AB-523 |

## Transitional queue decommission

The queue and TDQ→MQ/DCT inventory, ownership, migration targets, bridge flags, and deletion
preconditions are maintained in [queue-decommission.md](queue-decommission.md).

## Details

### I-01

**Claims daily extract (DAILY-EXTRACT.jcl)**

- **Source:** `jcl/DAILY-EXTRACT.jcl:31-96`.
- **Files:** `ACME.PAS.EXTRACT.POLICY.DAILY` (FB/200), sent as `/claims/import/policy_extract.dat`; `ACME.PAS.EXTRACT.COVERAGE.DAILY` (FB/150), sent as `/claims/import/coverage_extract.dat`.
- **Content:** a policy delta (AC/PN changed since yesterday 00:00) and a full snapshot of AC coverages (Q-38). The real record format is undefined in the repo (Q-41).
- **Timing:** 23:00 daily (CA-7 PAS0100).
- **Transport:** z/OS FTP client to `CLAIMSRV.ACME.LOCAL` as `ACMEFTP`. No credential is in the stream (OQ-02). No encryption, checksum or done-marker (Q-37).
- **Other end:** Claims Engine "VB6 batch import" (`jcl/DAILY-EXTRACT.jcl:14-15`), owned by the Claims system owner. Its import layout and its tolerance of overlapping deltas are unknown (M-11, OQ-10).

### I-02

**Actuarial monthly exposure (MONTHLY-EXPOSURE.jcl)**

- **Source:** `jcl/MONTHLY-EXPOSURE.jcl:26-71`.
- **File:** `ACME.PAS.EXPOSURE.MONTHLY.D&LYYMMDD` (FB/300), sent as `/actuarial/data/exposure_monthly.csv` and overwritten each month.
- **Content:** exposure grouped by type, branch, coverage type, territory and class (Q-42).
- **Timing:** the 1st of the month at 02:00 (CA-7 PAS0200).
- **Transport:** z/OS FTP client to `ACTUARIAL.ACME.LOCAL` as `ACMEFTP` (Q-37).
- **Other end:** Actuarial SAS programs for loss reserving and pricing (`jcl/MONTHLY-EXPOSURE.jcl:13-15`), owned by Actuarial (M-12).

### I-03

**Broker Portal SQL Server linked server**

- **Status (AB-528):** entitlement-filtered list API is available; portal-team sign-off remains pending.
- **Source:** README "Integration Points": "SQL Server linked server to DB2 (read-only ODBC)". `jcl/DAILY-EXTRACT.jcl:15` mentions a "linked server refresh" that no step performs (Q-39).
- **Content:** unknown. The tables, columns, query frequency and the SQL Server account used are not in the repo (M-08, OQ-16).
- **Other end:** Broker Portal team. Brokers see policy data through it, so object-level entitlements must be applied on the new path (Q-32).

### I-04

**Underwriting referral queue (POLNEW)**

- **Status (AB-528):** creation and renewal referrals use the transactional outbox and NEW-route bridge; see the [queue decommission plan](queue-decommission.md).
- **Source:** `cobol/programs/POLNEW.cbl:31-32`, `cobol/programs/POLNEW.cbl:240-253`.
- **Message:** the 125-byte POLICY-RECORD (`cobol/copybooks/POLICY-RECORD.cpy:11-49`), queue literal `ACME.PAS.UNDERWRITING.REQUEST`. The write is a CICS `WRITEQ TD`, so delivery to MQ depends on a TDQ-to-MQ definition that is not in the repo (M-04, Q-29, OQ-03).
- **Other end:** the consumer that starts underwriting (presumably a PUWR operator work queue or an automated trigger) is not identified; ask Underwriting operations and the MQ admin. Renewals put UW status back to PN but send no referral (Q-17).

### I-05

**Underwriting decision → FileNet document creation (UNDWRT)**

- **Status (AB-528):** document events use `pas.document.requests.v1`; the NEW-route bridge retains the legacy queue contract; see the [queue decommission plan](queue-decommission.md).
- **Source:** `cobol/programs/UNDWRT.cbl:45-46`, `cobol/programs/UNDWRT.cbl:236-243`; README "Document Management: IBM MQ events trigger document creation in FileNet".
- **Message:** the 125-byte POLICY-RECORD with UW status and risk score (3 digits, Q-20). The queue literal is `ACME.PAS.UNDERWRITING.DECISION`.
- **Other end:** the ECM / FileNet team. Which document templates each decision code produces is unknown (M-09, OQ-17). New-business, renewal and endorsement use the transitional document queue; template mapping remains open.

### I-06

**`ACMEINS.CLAIMS` (owned by Claims)**

- **Source:** POLRNW counts open claims (`cobol/programs/POLRNW.cbl:111-117`). UNDWRT counts claims and sums `INCURRED_AMOUNT` over 5 years (`cobol/programs/UNDWRT.cbl:137-143`).
- **Columns used:** `POLICY_NUMBER`, `CLAIM_STATUS` (value `'OPEN'`), `CLAIM_DATE`, `INCURRED_AMOUNT`. The DDL is absent (M-01).
- **Other end:** the Claims team owns the table, which sits in the same DB2 subsystem. The new platform reads claims history through a port with an adapter. Choosing the adapter (API, CDC or a shared view) is OQ-18.

### I-07

**Endorsement audit TS queue (POLEND)**

- **Status (AB-528):** the audit remains durable in the audit table and is associated with `PolicyEndorsed`; no TS-queue bridge is added; see the [queue decommission plan](queue-decommission.md).
- **Source:** `cobol/programs/POLEND.cbl:169-176`.
- **Message:** the 125-byte POLICY-RECORD on TS queue `ENDORSEMENT-AUDIT`, whose effective name is uncertain (Q-15).
- **Other end:** unknown. No program in the repo reads the queue. It is described as "regulatory compliance", so Compliance owns the requirement (OQ-06).

### I-08

**Premium batch TSO alert**

- **Source:** `jcl/PREMIUM-BATCH.jcl:45-48`.
- **Message:** `SEND 'PREMIUM BATCH COMPLETED WITH WARNINGS' USER(PASADMIN)`. It is sent on RC 0 and 4 and never on failure (Q-35).
- **Other end:** mainframe operations (mainframe-ops@acme.com).

### I-09

**CA-7 schedule**

- **Source:** job header comments only: PAS0050 PREMIUM-BATCH daily 01:00 (`jcl/PREMIUM-BATCH.jcl:7`), PAS0100 DAILY-EXTRACT daily 23:00 (`jcl/DAILY-EXTRACT.jcl:7`), PAS0200 MONTHLY-EXPOSURE 1st 02:00 (`jcl/MONTHLY-EXPOSURE.jcl:7`).
- **Implication:** the extract at 23:00 runs before the premium batch at 01:00, so a day's PREMBAT results are never in that night's extract. They never trigger a delta row anyway (Q-36, Q-38). Triggers, dependencies, calendars and the rerun procedure are in CA-7, not in the repo (M-05).
- **Other end:** mainframe operations (scheduling).

### I-10

**REST facade (java-facade)**

- **Source:** `java-facade/src/main/resources/application.properties:9-17`, `java-facade/src/main/java/com/acme/insurance/pas/controller/PolicyController.java:33-48`, `java-facade/src/main/java/com/acme/insurance/pas/repository/PolicyRepository.java:32-49`.
- **Contract:** read-only JSON for a policy and its coverages, pinned by `compat/api/snapshots/policies-v1.json` and the compat gate.
- **Transport:** JDBC to `mainframe.acme.local:5023/DBPD` as `PASFACAD` (from the environment). There is no caller authentication (Q-32). The README says it runs in Docker on a Linux VM.
- **Other end:** the Java facade owner (t.nguyen@acme.com) and the "modern client systems" calling it. The list of callers is unknown (OQ-19).

### I-11

**3270 operators (CICS/BMS)**

- **Source:** the five online programs. Map and mapset names are listed in `behaviour-inventory.md` §3.
- **Other end:** CSRs and underwriters (README: POLQRY is "used by CSRs and underwriters"). The PAS product owner owns the user-facing behaviour. The map sources are absent (M-02).

### I-12

**Premium report dataset**

- **Source:** `jcl/PREMIUM-BATCH.jcl:28-31`, `cobol/programs/PREMBAT.cbl:85-100`, `cobol/programs/PREMBAT.cbl:226-250`.
- **Content:** a run header, one line per policy, and three totals lines.
- **Other end:** unknown (operations and/or Finance). Retention and readers are an open question (OQ-20).
