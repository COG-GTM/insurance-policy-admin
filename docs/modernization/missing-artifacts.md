# Referenced but missing artifacts (AB-517)

Things the PAS source refers to, or depends on, that are **not in this repository**. Each
needs to be obtained (or confirmed as not existing) before the capability that depends on
it can be migrated with confidence. Source control today is split: COBOL/JCL in IBM RTC,
facade in SVN (README L123-127), so most of these probably live in RTC, the CICS CSD, CA-7,
MQ or the DB2 catalog.

| | |
|---|---|
| Epic / ticket | AB-516 / AB-517 |
| Reviews | Pending: Mainframe team lead. Pending: DBA team (db2-admin@acme.com). |

See also: [inventory](inventory.md) · [integrations](integrations.md) ·
[quirk register](quirk-register.md) · [open questions](open-questions.md).

| ID | Artifact | Referenced at | Needed for | Likely owner / location |
|----|----------|---------------|------------|-------------------------|
| [M-01](#m-01) | `ACMEINS.CLAIMS` table DDL and interface | `POLRNW.cbl` L114; `UNDWRT.cbl` L140 | AB-525, AB-526 | Claims team / DB2 catalog |
| [M-02](#m-02) | BMS mapsets (10 maps in 5 mapsets) | all CICS programs | AB-524..AB-526 | Mainframe team / RTC |
| [M-03](#m-03) | CICS resource definitions (CSD) | transactions, TD/TS queues | AB-524..AB-528 | CICS systems programmers |
| [M-04](#m-04) | MQ object definitions and FileNet trigger | `POLNEW.cbl` L31-32; `UNDWRT.cbl` L45-46; README L102 | AB-528 | MQ admins / ECM team |
| [M-05](#m-05) | CA-7 schedule definitions | JCL headers | AB-523, AB-527, AB-530 | Batch operations |
| [M-06](#m-06) | FTP credentials, NETRC, receiving-side jobs | `DAILY-EXTRACT.jcl` L86-94; `MONTHLY-EXPOSURE.jcl` L63-70 | AB-527 | Mainframe ops; Claims; Actuarial |
| [M-07](#m-07) | DB2 precompile/bind options, plans, DCLGEN | `PREMIUM-BATCH.jcl` L34; all `EXEC SQL` | AB-521, AB-529 | DBA team |
| [M-08](#m-08) | Broker Portal linked-server definition | README L101 | AB-528 | Broker Portal team |
| [M-09](#m-09) | Production samples of extract files and report | JCL output DDs | AB-523, AB-527 | Mainframe ops |
| [M-10](#m-10) | Code the comments say exists | PREMBAT L113; POLRNW L15; DAILY-EXTRACT L11, L20 | AB-522, AB-526, AB-527 | Mainframe team / RTC |
| [M-11](#m-11) | DB2 security: auth ids, grants, RACF | facade `application.properties` L15; linked server | AB-524, AB-528 | DBA / Security |
| [M-12](#m-12) | Customer-master and territory-factor maintenance | `POLICY_HOLDERS`, `TERRITORY_FACTORS` | AB-521, AB-522 | Unknown |

## Details

### M-01

**`ACMEINS.CLAIMS`.** Read by POLRNW (open-claims count) and UNDWRT (5-year count and
incurred sum). Columns used: `POLICY_NUMBER`, `CLAIM_STATUS` (value `'OPEN'`), `CLAIM_DATE`,
`INCURRED_AMOUNT`. Not in `sql/ddl/create-tables.sql`. The epic puts the Claims engine out of
scope and requires an agreed read interface; we need the DDL (types, indexes), the status code
set, and the Claims team's preferred interface.

### M-02

**BMS mapsets.** No `.bms` source or generated symbolic-map copybooks are in the repo. Every
program receives *into* `POLICY-RECORD`, so the symbolic maps must overlay that layout.

| Mapset | Maps | Program |
|--------|------|---------|
| `POLNMAPS` | `POLNMAP` (input), `POLNCONF` (confirmation) | POLNEW L98-99, L257-258 |
| `POLRMAPS` | `POLRMAP`, `POLRCONF` | POLRNW L80-81, L185-186 |
| `POLEMAPS` | `POLEMAP`, `POLECONF` | POLEND L77-78, L180-181 |
| `POLQMAPS` | `POLQMAP`, `POLQDET` | POLQRY L64-65, L155-156 |
| `UWRTMAPS` | `UWRTMAP`, `UWRTRES` | UNDWRT L86-87, L247-248 |

Needed to know which fields users actually enter (e.g. whether POLEND's map carries an
endorsement type, [Q-10](quirk-register.md#q-10)) and what the screens show
([Q-30](quirk-register.md#q-30)).

### M-03

**CICS CSD.** Transaction → program definitions (`PNEW`, `PRWL`, `PEND`, `PQRY`, `PUWR`),
TD queue definitions (the programs use 48-byte queue names although TD queue names are at
most 4 characters, so there must be an indirection), TS model for `ENDORSEMENT-AUDIT`
(17 characters, above the 16-character `QNAME` limit), security (transaction/resource
security, RACF profiles), and any `START`/trigger-level definitions that kick off `PUWR`.

### M-04

**MQ objects and FileNet trigger.** Queue managers, `ACME.PAS.UNDERWRITING.REQUEST`,
`ACME.PAS.UNDERWRITING.DECISION`, any CICS–MQ bridge / adapter, and the queue(s) FileNet
listens on. README L102 says MQ events trigger FileNet document creation but no code names a
FileNet queue. Also missing: any message descriptor (MQMD) conventions, persistence, and a
copybook for the message body (today it is `POLICY-RECORD`).

### M-05

**CA-7 schedules.** Only header comments exist: `PAS0050` PREMIUM-BATCH daily 01:00,
`PAS0100` DAILY-EXTRACT daily 23:00, `PAS0200` MONTHLY-EXPOSURE 1st 02:00. Need
predecessors/successors (e.g. does DAILY-EXTRACT wait for online close? does anything wait for
PREMBAT?), calendars, restart/rerun instructions, and the `&LYYMMDD` symbol source.

### M-06

**FTP configuration.** Both FTP steps log in as `ACMEFTP` with no credential in the input
stream, so a NETRC dataset or security exit supplies it. Also needed: the Claims Engine VB6
import job and schedule on `CLAIMSRV.ACME.LOCAL`, and the SAS programs that read
`exposure_monthly.csv`, to know the exact bytes they parse.

### M-07

**DB2 build artefacts.** Precompiler/bind options (`DATE`/`TIME` format, `APOST`, `HOST`),
package collections and plans (`PASBATCH`, `DSNTEP12`), DCLGEN copybooks (POLNEW hand-codes
`DCLPOLICIES` with X(10) dates, others bind DB2 `DATE` columns to PIC 9(8) host variables),
the DB2 view/alias layer if any, and RUNSTATS/REORG jobs. Needed to explain how dates
convert today ([OQ-17](open-questions.md#oq-17)) and for the data migration.

### M-08

**Broker Portal linked server.** SQL Server linked-server definition, ODBC DSN, the DB2
auth id it uses, and the queries/views the portal runs. Without it we cannot know which
columns the portal depends on.

### M-09

**Production samples.** At least one recent `ACME.PAS.EXTRACT.POLICY.DAILY`,
`ACME.PAS.EXTRACT.COVERAGE.DAILY`, `ACME.PAS.EXPOSURE.MONTHLY.Dyymmdd` and
`ACME.PAS.REPORTS.PREMIUM.Dyymmdd`, plus job logs (SYSPRINT/JESMSGLG) for the last few
PREMIUM-BATCH runs to see RC and SQLCODE -803 counts ([Q-22](quirk-register.md#q-22)).
These become the byte-level goldens for AB-527.

### M-10

**Code the comments refer to.**

- A PREMBAT version that "loads all rows" of `TERRITORY_FACTORS` (`PREMBAT.cbl` L113).
- A mass-renewal batch ("Batch mode supported via PREMBAT for mass renewals", `POLRNW.cbl` L15).
- The DAILY-EXTRACT "encryption step" (`DAILY-EXTRACT.jcl` L11) and the customer extract
  step for `ACME.PAS.EXTRACT.CUSTOMER.DAILY` (L20).
- POLQRY's "API flag check for facade" (`POLQRY.cbl` L10).
- POLNEW's "terrorism coverage check" and "cyber liability option" (`POLNEW.cbl` L9-10) and
  UNDWRT's "terrorism risk scoring", "catastrophe zone check", "cyber risk assessment"
  (`UNDWRT.cbl` L9-11): no such logic is in the source; `WS-ZONE-RISK-FACTOR` (UNDWRT L44)
  is declared and unused.

Either these exist in RTC (and this repo is stale) or they were never built.

### M-11

**DB2 security.** Auth ids for the facade (`DB2_USERNAME`, default `PASFACAD`,
`application.properties` L15), the Broker Portal linked server, batch plans, and CICS
(DB2CONN/DB2ENTRY), with their grants. Needed for least-privilege design in AB-524/AB-528 and
for decommissioning in AB-530.

### M-12

**Reference/master data maintenance.** No PAS program writes `POLICY_HOLDERS` or
`TERRITORY_FACTORS`. The process (CRM feed, another CICS app, DBA loads) and its owner are
unknown.
