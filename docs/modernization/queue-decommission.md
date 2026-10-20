# Queue, CICS and linked-server decommission list (AB-528)

Every legacy messaging object, CICS definition and linked-server object that PAS used, with its
Java replacement. Sources: `cobol/programs/*.cbl`, `jcl/*.jcl`, `README.md` and the AB-517
inventory ([inventory.md](inventory.md), [integrations.md](integrations.md),
[missing-artifacts.md](missing-artifacts.md)). The real object definitions (CICS CSD, MQ objects,
SQL Server linked server) are not in the repository (M-03, M-04, M-08), so physical names are
listed where the code shows them and marked *TBC* where only the role is known.

Nothing here is switched off by AB-528. Each object is retired by its owner after the parallel
run (AB-529) and cut-over (AB-530). **Status of every row: Pending: Mainframe team** (with the
co-owner named in the Owner column).

## TDQ / TSQ / MQ queues

| # | Object | Type | Source | Written by | Replaced by | Owner | Status |
|---|--------|------|--------|-----------|-------------|-------|--------|
| D-01 | `ACME.PAS.UNDERWRITING.REQUEST` | CICS TD queue (48-byte name in code; physical TDQ/extrapartition id and CICS–MQ bridge *TBC*) | `POLNEW.cbl` L31-32, L243-250 | POLNEW (PNEW) after POLICIES/COVERAGES insert | `PolicyCreated` on `pas.policy.events.v1`; transitional `uw-request-bridge` rebuilds the 125-byte POLICY-RECORD (`LegacyQueueBridge`) | Mainframe team + Underwriting ops | Pending: Mainframe team |
| D-02 | `ACME.PAS.UNDERWRITING.DECISION` | CICS TD queue (same caveat) | `UNDWRT.cbl` L45-46, L237-243 | UNDWRT (PUWR) after decision insert | `UnderwritingDecided` / `UnderwritingReferred` on `pas.underwriting.events.v1`; transitional `uw-decision-bridge` | Mainframe team + Underwriting ops | Pending: Mainframe team |
| D-03 | `ENDORSEMENT-AUDIT` | CICS TS queue (17-char name exceeds the 16-char QNAME limit; real name *TBC*) | `POLEND.cbl` L171-176 | POLEND (PEND) after the premium update | `PolicyEndorsed` on `pas.policy.events.v1` + `pas.endorsements` audit rows (AB-526) | Mainframe team + Compliance | Pending: Mainframe team |
| D-04 | MQ queue(s) FileNet listens on | IBM MQ 9.2 local/remote queue, trigger monitor/process definitions (*TBC*) | `README.md` L51, L102; integrations I-06 | MQ routing of D-01/D-02/D-03 or an MQ subscription (*TBC*) | `DocumentRequested` on `pas.document.requests.v1`; transitional `filenet-document-bridge` (`pas.integration.filenet-queue`) | MQ admins + Document/FileNet team | Pending: Mainframe team |
| D-05 | CICS–MQ bridge / adapter definitions for D-01, D-02 (MQCONN, initiation queue, trigger process) | CICS/MQ resource definitions (*TBC*) | missing-artifacts M-03, M-04 | — | Kafka + bridges above; retired with D-01/D-02 | MQ admins | Pending: Mainframe team |
| D-06 | MQ queue manager objects used only by PAS (channels, alias/remote queues for the above) | IBM MQ (*TBC*) | missing-artifacts M-04 | — | none (Kafka) | MQ admins | Pending: Mainframe team |

## CICS definitions

| # | Object | Type | Source | Replaced by | Owner | Status |
|---|--------|------|--------|-------------|-------|--------|
| D-07 | `PNEW` → `POLNEW` | Transaction + program (CSD) | `POLNEW.cbl` L2, L5 | `POST /api/v1/policies` (AB-525) | Mainframe team (CICS sysprogs) | Pending: Mainframe team |
| D-08 | `PRWL` → `POLRNW` | Transaction + program | `POLRNW.cbl` L2, L5 | renewal API (AB-526) | Mainframe team | Pending: Mainframe team |
| D-09 | `PEND` → `POLEND` | Transaction + program | `POLEND.cbl` L2, L5 | endorsement API (AB-526) | Mainframe team | Pending: Mainframe team |
| D-10 | `PQRY` → `POLQRY` | Transaction + program | `POLQRY.cbl` L2, L5 | `GET /api/v1/policies/...` (AB-524) | Mainframe team | Pending: Mainframe team |
| D-11 | `PUWR` → `UNDWRT` | Transaction + program | `UNDWRT.cbl` L2, L5 | `POST /api/v1/policies/{n}/underwriting-decisions` (AB-525) | Mainframe team | Pending: Mainframe team |
| D-12 | BMS mapsets/maps used by the five transactions (`POLRCONF`, `POLECONF`, `UWRTRES`, ...) | Mapset (CSD) | `SEND MAP` in the programs | APIs above | Mainframe team | Pending: Mainframe team |
| D-13 | TD queue definitions behind D-01/D-02 and TS model / TSQ security for D-03 | DCT / TSMODEL entries (*TBC*) | missing-artifacts M-03 | — | Mainframe team | Pending: Mainframe team |
| D-14 | Transaction/resource security profiles for the above (RACF) | RACF (*TBC*) | missing-artifacts M-03, M-11 | OIDC scopes (ADR 0001) | Mainframe team + Security | Pending: Mainframe team |
| D-15 | DB2 plans/packages bound for the CICS programs and `PASBATCH` | DB2 plan/package (*TBC*; `PLAN(PASBATCH)` in `PREMIUM-BATCH.jcl`) | `jcl/PREMIUM-BATCH.jcl` | pas-batch (AB-523, AB-527) | Mainframe team + DBA | Pending: Mainframe team |

## Linked-server / ODBC objects (Broker Portal)

| # | Object | Type | Source | Replaced by | Owner | Status |
|---|--------|------|--------|-------------|-------|--------|
| D-16 | SQL Server linked server to DB2 `DBPD` (name *TBC*) | SQL Server linked server | `README.md` L101; integrations I-03 | AB-524/AB-528 inquiry API ([broker-portal-migration.md](broker-portal-migration.md)) | Broker Portal team + Mainframe team | Pending: Mainframe team |
| D-17 | ODBC DSN / IBM DB2 client on the portal SQL Server host | ODBC DSN (*TBC*) | integrations I-03 | HTTPS + OIDC client | Broker Portal team | Pending: Mainframe team |
| D-18 | DB2 auth id and GRANTs used by the linked server | DB2 auth id / RACF (*TBC*) | missing-artifacts M-11 | portal OIDC client with `policy:read` | DBA + Security | Pending: Mainframe team |
| D-19 | Portal views / synonyms over the linked server and the "linked server refresh" fed by DAILY-EXTRACT | SQL Server objects + extract consumer (*TBC*) | `jcl/DAILY-EXTRACT.jcl` L13-15 | API reads (PQ-01..PQ-06); extracts move to AB-527 | Broker Portal team | Pending: Mainframe team |
| D-20 | DB2 `DBPD` network path from the portal host (port 5023, `mainframe.acme.local`) | Firewall rule (*TBC*) | facade `application.properties` L14-17 (same endpoint) | gateway route to pas-policy-service | Network + Mainframe team | Pending: Mainframe team |

## Order

1. FileNet and Underwriting confirm the bridge output in their test environments (Pending:
   Document/FileNet team, Pending: Underwriting team), then the bridges are enabled in production
   (`PAS_INTEGRATION_BRIDGES_ENABLED=true`) during the parallel run.
2. After cut-over (AB-530): retire D-07..D-15 with the CICS region, D-01..D-06 once no consumer
   reads them, D-16..D-20 once the portal runs on the API.
3. Retire the transitional bridges themselves when FileNet/Underwriting consume Kafka directly
   (proposed ticket in [open-questions.md](open-questions.md)).
