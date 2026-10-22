# Referenced but not in this repository (AB-517)

Each item below is used or named by the code or README but has no source in the repository. Without
it, legacy behaviour can't be fully reproduced or verified. "Holder" is the team expected to supply it.

### M-01

**`ACMEINS.CLAIMS` DDL.** Read by `cobol/programs/POLRNW.cbl:111-117` and `cobol/programs/UNDWRT.cbl:137-143` (columns `POLICY_NUMBER`, `CLAIM_STATUS`, `CLAIM_DATE`, `INCURRED_AMOUNT`). It is not in `sql/ddl/create-tables.sql`. Holder: Claims team / DBA (db2-admin@acme.com).

### M-02

**BMS mapsets and maps.**

| Mapset | Maps | Used by |
|--------|------|---------|
| `POLNMAPS` | `POLNMAP`, `POLNCONF` | `cobol/programs/POLNEW.cbl:96-106`, `cobol/programs/POLNEW.cbl:255-263` |
| `POLRMAPS` | `POLRMAP`, `POLRCONF` | `cobol/programs/POLRNW.cbl:79-84`, `cobol/programs/POLRNW.cbl:183-190` |
| `POLEMAPS` | `POLEMAP`, `POLECONF` | `cobol/programs/POLEND.cbl:76-81`, `cobol/programs/POLEND.cbl:178-185` |
| `POLQMAPS` | `POLQMAP`, `POLQDET` | `cobol/programs/POLQRY.cbl:63-68`, `cobol/programs/POLQRY.cbl:153-160` |
| `UWRTMAPS` | `UWRTMAP`, `UWRTRES` | `cobol/programs/UNDWRT.cbl:85-90`, `cobol/programs/UNDWRT.cbl:245-251` |

The programs receive the maps straight into copybook records. Without the map sources (and the generated symbolic maps), the field-level input and output contract of each screen is only inferred. Holder: mainframe team.

### M-03

**CICS CSD definitions.** These cover TRANSACTION PNEW/PRWL/PEND/PQRY/PUWR → PROGRAM, PROGRAM entries, DB2CONN/DB2ENTRY (CICS plan names), TDQUEUE and TSMODEL definitions. Holder: mainframe team (CICS systems programming).

### M-04

**TDQ / MQ definitions.**

- The TD queue(s) behind `WRITEQ TD` in POLNEW and UNDWRT (`cobol/programs/POLNEW.cbl:243-248`, `cobol/programs/UNDWRT.cbl:237-242`) and how they reach IBM MQ.
- The MQ queue manager, queues `ACME.PAS.UNDERWRITING.REQUEST` / `.DECISION`, and any trigger/initiation queues.
- The TS queue used by POLEND (`cobol/programs/POLEND.cbl:171-175`).

Holder: mainframe team / MQ admin.

### M-05

**CA-7 schedule.** Job definitions PAS0050, PAS0100 and PAS0200, their triggers, predecessors, calendars (holidays, month-end), and restart/rerun instructions. Only the header comments exist (`jcl/PREMIUM-BATCH.jcl:7`, `jcl/DAILY-EXTRACT.jcl:7`, `jcl/MONTHLY-EXPOSURE.jcl:7`). Holder: mainframe operations.

### M-06

**DB2 plans, packages and precompile/bind options.** These are plan `PASBATCH` (`jcl/PREMIUM-BATCH.jcl:34`), `DSNTEP12` (`jcl/DAILY-EXTRACT.jcl:42`), the CICS plans, DCLGEN members (POLNEW carries an inline DCLGEN at `cobol/programs/POLNEW.cbl:43-62`; the others have none), and the DATE/TIME precompiler options or date exit (Q-28). Holder: DBA.

### M-07

**RACF profiles.** These cover transaction-level security for the five transaction IDs, user `ACMEFTP`, facade user `PASFACAD`, TSO user `PASADMIN`, and the batch job user. They are needed to map today's access model onto JWT scopes and entitlements (Q-32). Holder: Security / RACF admin.

### M-08

**Broker Portal linked-server configuration.** The SQL Server linked-server definition, the ODBC DSN, the DB2 auth ID, and the queries or views the portal runs (README "Integration Points"). Holder: Broker Portal team.

### M-09

**FileNet consumer.** The MQ-to-FileNet bridge or listener, and the mapping from message/decision code to document class/template. Holder: ECM / FileNet team.

### M-10

**FTP configuration.** The NETRC or other credential source for `ACMEFTP` (no password appears in `jcl/DAILY-EXTRACT.jcl:88-94` or `jcl/MONTHLY-EXPOSURE.jcl:65-70`), the FTP.DATA client profile (translation table: EBCDIC→ASCII conversion, record delimiters), and the receiving server configuration. Holder: mainframe operations / network.

### M-11

**Claims Engine import program and layout.** The VB6 batch import that reads `policy_extract.dat` and `coverage_extract.dat` (`jcl/DAILY-EXTRACT.jcl:14`), with its expected record layout and its handling of duplicates. Holder: Claims system owner.

### M-12

**Actuarial SAS programs** that read `exposure_monthly.csv` (`jcl/MONTHLY-EXPOSURE.jcl:13-15`). Holder: Actuarial.

### M-13

**`ACME.PAS.LOADLIB` contents.** DAILY-EXTRACT STEP010 runs DSNTEP2 from this library (`jcl/DAILY-EXTRACT.jcl:42-43`), and it may be site-modified to write to POLOUT (Q-41). Holder: mainframe team.

### M-14

**Dataset housekeeping.** Any job that deletes or renames `ACME.PAS.EXTRACT.*.DAILY` between runs, GDG bases, and retention for the report and exposure datasets (Q-40). Holder: mainframe operations.

### M-15

**Status lifecycle jobs.** Whatever sets PN→AC, AC→EX (expiry) and AC→LP (lapse) and CN (cancellation). No program here does it (Q-24). Holder: mainframe team / PAS product owner.

### M-16

**Rating reference data.** `TERRITORY_FACTORS` rows and the source of the class factor table (`cobol/programs/PREMBAT.cbl:56-59`), which has no DDL. Holder: Actuarial.

### M-17

**Production samples for parity.** Recent `PREMRPT` reports, the extract files as received by Claims and Actuarial, and anonymised DB2 unloads. Without these, golden files can only be built from the code, which is itself ambiguous (Q-41). Holder: mainframe operations, with Security approval for any PII.

### M-18

**Consumer of `ENDORSEMENT-AUDIT`.** No reader is in the repo (Q-15). Holder: Compliance / mainframe team.
