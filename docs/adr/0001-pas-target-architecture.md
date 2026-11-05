# ADR 0001: PAS target architecture

- **Status:** Proposed
- **Date:** 2026-11-05
- **Ticket:** AB-518 (epic AB-516)
- **New components:** see [`0001-components.json`](0001-components.json)
- **Deciders:** Architecture Review Board (pending, see [ARB submission](arb/0001-arb-submission.md))
- **Consulted:** Mainframe team, DBA team, Security, Data governance, Platform team, Claims, Actuarial, Broker Portal, ECM (all pending)
- **Inputs:** [behaviour inventory](../modernization/behaviour-inventory.md), [quirk register](../modernization/quirk-register.md), [open questions](../modernization/open-questions.md) (AB-517)

This ADR fixes the target architecture for moving the Policy Administration System (PAS) off COBOL/CICS/DB2 to Java 21 / Spring Boot 3. Every later ticket of AB-516 builds against it. It decides **what** we build and **why**. Detailed designs (schemas, event payloads, runbooks) belong to the ticket named in each decision.

Contents:
1. [Context](#context)
2. [Decision drivers](#decision-drivers)
3. [Decisions](#decisions) (D1..D9)
4. [Target architecture overview](#target-architecture-overview)
5. [New components and data stores](#new-components-and-data-stores)
6. [Consequences](#consequences)
7. [Risks](#risks)
8. [Open items and follow-ups](#open-items-and-follow-ups)

## Context

Facts from the AB-517 discovery, all traceable to source in this repository:

- **Five CICS transactions** (PNEW/POLNEW, PRWL/POLRNW, PEND/POLEND, PQRY/POLQRY, PUWR/UNDWRT) and **three batch jobs** (PREMIUM-BATCH/PREMBAT, DAILY-EXTRACT, MONTHLY-EXPOSURE) operate on **one DB2 schema, ACMEINS**. `POLICIES` is read or written by all six programs and all three jobs ([CRUD matrix](../modernization/behaviour-inventory.md#2-db2-tables-and-crud-matrix)). Every message the CICS programs emit is the same 125-byte `POLICY-RECORD`.
- **No transaction boundary crosses programs**, but they share rules: the premium formula in PREMBAT is the base for renewal pricing ([Q-05](../modernization/quirk-register.md#q-05), [Q-27](../modernization/quirk-register.md#q-27)), and the underwriting risk score reads policy, policyholder and claims data ([UNDWRT](../modernization/behaviour-inventory.md#puwr-undwrt-underwriting-decision)).
- **Messaging:** POLNEW and UNDWRT write to CICS TD queue names longer than four characters, so both land in one TD queue; the routing on to IBM MQ, FileNet and the underwriting work queue is outside this repository ([Q-13](../modernization/quirk-register.md#q-13), [FU-06](../modernization/open-questions.md)). The endorsement "audit trail" is a volatile CICS TS queue ([Q-23](../modernization/quirk-register.md#q-23)).
- **Security:** the Spring Boot 1.5 read facade has no authentication and an open management endpoint ([Q-36](../modernization/quirk-register.md#q-36)). The Claims and Actuarial feeds go out in clear text with the z/OS FTP client ([Q-32](../modernization/quirk-register.md#q-32)). `POLICY_HOLDERS` holds regulated data (date of birth, SSN last 4, tax id, credit score).
- **Batch:** PREMBAT is single-threaded, one unit of work for the whole book (about 4 hours) and not rerunnable ([Q-25](../modernization/quirk-register.md#q-25), [Q-26](../modernization/quirk-register.md#q-26), [Q-38](../modernization/quirk-register.md#q-38)).
- **Integrations** I-1..I-10 ([inventory §7](../modernization/behaviour-inventory.md#7-integration-points)): Claims extract (I-1), Actuarial feed (I-2), Broker Portal reading DB2 directly over an ODBC linked server (I-3), underwriting request/decision messages (I-4, I-5), FileNet triggers (I-6), the Claims-owned `ACMEINS.CLAIMS` table (I-7), facade consumers (I-8), endorsement audit (I-9), operator notification (I-10).
- **Contracts that must not move** during the epic: policy numbers and `POLICY_SEQ`, cent-level premiums, the copybook layouts, the extract LRECLs and the facade's JSON. The compat gate ([compat/README.md](../../compat/README.md)) enforces them on every PR.
- **Only one change is planned for business reasons:** table-driven rating factors ([FU-03](../modernization/open-questions.md)). Everything else moves at parity first; each quirk has a preserve/fix proposal.

## Decision drivers

| # | Driver | Source |
|---|---|---|
| DR-1 | Behaviour parity with COBOL to the cent, except signed-off quirk fixes | Epic success criteria, quirk register |
| DR-2 | Incremental migration: one capability at a time, each reversible, DB2 stays system of record until dual-run sign-off | Epic approach |
| DR-3 | All policy reads and writes behind authenticated, authorised APIs | Epic success criteria, Q-36 |
| DR-4 | No unencrypted file transfers | Epic success criteria, Q-32 |
| DR-5 | Premium batch under 30 minutes for the full book, safe to rerun | Epic success criteria, Q-26, Q-38 |
| DR-6 | Downstream byte layouts and message payloads unchanged unless the consumer signs off | Epic risks, compat gate |
| DR-7 | Remove the mainframe dependency (MIPS cost, key-person risk) at the end of the epic | Epic "why now" |
| DR-8 | A small team must be able to build, run and support it; prefer the organisation's standard platform over new technology | Delivery |

## Decisions

### D1: Service boundaries: one policy service plus one batch application

**Decision.** Build **one deployable policy service** (`pas-policy-service`) that owns policy lifecycle (new business, renewal, endorsement), rating, underwriting and inquiry, and **one batch application** (`pas-batch`) for the premium run and the two extracts. Both are built from one Maven multi-module codebase and share the domain, rating and underwriting modules. Inside the service, capabilities are separate modules with enforced dependency rules (a modular monolith), so a module can be split out later without rewriting it.

Proposed module layout (AB-519 creates it; names may be refined there, the dependency rules may not):

| Module | Contents | May depend on |
|---|---|---|
| `pas-domain` | Policy, Coverage, Premium, Endorsement, UnderwritingDecision, Policyholder value types; enums for the COBOL 88-levels; `BigDecimal` money with COBOL scale | nothing (no Spring) |
| `pas-rating` | Rating component ported from PREMBAT 3200-CALCULATE-PREMIUM (AB-522) | `pas-domain` |
| `pas-underwriting` | Risk score and decision bands ported from UNDWRT (AB-525) | `pas-domain` |
| `pas-persistence` | Repositories and Flyway migrations for the PAS schema (AB-521) | `pas-domain` |
| `pas-events` | Versioned event schemas and the outbox publisher (AB-528) | `pas-domain` |
| `pas-policy-service` | Spring Boot application: REST API, security, lifecycle use cases | all library modules |
| `pas-batch` | Spring Boot application: Spring Batch jobs (AB-523, AB-527) | all library modules except `pas-policy-service` |

Library modules never depend on an application module. The rule is checked by an architecture test (ArchUnit or Spring Modulith) added in AB-519.

**Why.** The five transactions are one aggregate (the policy) on one schema: splitting by CICS transaction would put a network call or a distributed transaction between steps that are one DB2 unit of work today (for example POLNEW inserts the policy and its coverages and requests underwriting). Rating is shared by batch and online flows, and underwriting reads the same policy and policyholder rows. Separating batch from the online service keeps a heavy premium run from competing with API latency and lets each scale independently, while sharing the exact same rating code, which is a parity requirement.

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| One policy service + one batch application from a shared modular codebase | **Chosen** | One aggregate, one schema, shared rating code, independent scaling of batch, small team |
| One microservice per CICS transaction (5 services + batch) | Rejected | Splits one aggregate and one table across services; distributed transactions or chatty calls for what is one unit of work today; five times the deployment and on-call surface for no business gain |
| Domain microservices (policy, rating, underwriting) each with its own database | Rejected for now | Data is not separable today (rating and underwriting read policy rows directly); revisit once the modules are stable, the module boundaries make that split possible |
| Batch jobs inside the policy service | Rejected | Long-running batch shares pods, connection pool and heap with the API; harder to schedule and scale |
| Extend or upgrade the existing `java-facade` | Rejected | Java 8 / Spring Boot 1.5 are end of life; read-only design; retired by the epic (AB-524) |

### D2: Runtime: Java 21, Spring Boot 3, Spring Batch, containers on the standard platform

**Decision.** Java 21 (LTS), Spring Boot 3.x (the supported line at the time AB-519 pins the parent POM), Spring Batch 5 for batch, Maven multi-module build. Both applications ship as OCI container images built in CI, run as non-root on a minimal JRE 21 base image, and deploy to the organisation's standard container platform (Kubernetes) with health, readiness and liveness probes. Batch jobs are launched by the platform scheduler (Kubernetes CronJob or the enterprise scheduler, whichever the platform team mandates) and use the Spring Batch job repository for restart. Configuration comes from environment and the platform secrets manager; no secret defaults in images or property files (fixes the pattern in [Q-36](../modernization/quirk-register.md#q-36)).

**Why.** Matches the epic, the repo's existing Maven build and the organisation's skills. Spring Batch gives chunking, partitioning, restart and run metadata, which are exactly what PREMBAT lacks (DR-5). The compat gate already builds every Maven module with the JDK its POM declares, so a Java 21 module drops into CI unchanged.

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| Java 21 + Spring Boot 3 + Spring Batch on the container platform | **Chosen** | Epic mandate, LTS, team skills, batch restart/partitioning out of the box |
| Java 17 + Spring Boot 3 | Rejected | Older LTS with less runway; no benefit for a new build |
| Quarkus or Micronaut | Rejected | Smaller enterprise footprint and skills base; no batch framework equivalent to Spring Batch |
| GraalVM native images | Rejected for now | Faster start-up is not a driver; adds build complexity and reflection constraints for JDBC drivers |
| Java on z/OS (zIIP) next to the COBOL | Rejected | Keeps the mainframe dependency the epic is meant to remove (DR-7) |
| VMs instead of containers | Rejected | Not the standard platform; no image-based CI/CD |

### D3: Target database, migration and side-by-side running

**Decision.**
1. **Target database: PostgreSQL 16** on the platform's managed PostgreSQL service, schema `acmeins`. Table and column names, key columns, `CHAR` widths and `DECIMAL` precision/scale are kept as in `sql/ddl/create-tables.sql`, so copybook and extract layouts map 1:1. `POLICY_SEQ` becomes a PostgreSQL sequence with the same start, increment and no cycle.
2. **Schema migrations: Flyway**, versioned per ticket in the ranges the epic allocates. Applied migrations are immutable (the compat gate already fails edits to them).
3. **Exactly one system of record per point in time; no dual writes and no bi-directional sync.**
   - *Transition (until AB-529 sign-off):* DB2 is the system of record. A capability that has moved (D7) reads and writes **DB2** through the `pas-persistence` adapter over JDBC, and takes policy numbers from DB2 `POLICY_SEQ`, so COBOL and Java never disagree about data or numbering. Log-based change data capture (CDC) replicates DB2 to PostgreSQL continuously, so the target is always populated for reconciliation and shadow runs. New tables that must commit in the same unit of work as a business write (the outbox DS-04 and the audit table DS-05) are therefore **DB2** tables during the transition, in the separate schema `PASAPP` (see D4, *Where the outbox and audit tables live*).
   - *Shadow and parallel runs:* the Java batch and extract jobs write their outputs to a separate `acmeins_shadow` schema in PostgreSQL, never to DB2 and never to the CDC-fed `acmeins` tables, and the results are diffed against the mainframe outputs (AB-529).
   - *Cutover (AB-530):* after one clean billing cycle, freeze writes, drain CDC, reconcile, set the PostgreSQL sequence above the DB2 high-water mark plus the DB2 sequence cache (50), switch `pas-persistence` to PostgreSQL, and reverse CDC (PostgreSQL to DB2) for the agreed rollback window so CICS/DB2 can take over again.
   - *Rollback (during the rollback window):* freeze writes on PostgreSQL, drain and reconcile reverse CDC, then restart DB2 `POLICY_SEQ` (`ALTER SEQUENCE ... RESTART WITH`) above the highest policy number issued by PostgreSQL plus the DB2 sequence cache (50), before CICS new business is re-enabled. Reverse CDC copies rows but does not consume sequence values, so without this step POLNEW would re-issue numbers already used on PostgreSQL. Number generation after rollback is part of the AB-529 rollback rehearsal.
4. **CDC tooling:** log-based CDC is required because only `POLICIES` and `POLICY_HOLDERS` carry a `LAST_UPDATED` change timestamp. `ENDORSEMENTS` (`PROCESSED_DATE`) and `UNDERWRITING_DECISIONS` (`CREATED_TIMESTAMP`) only have insert timestamps, and `COVERAGES`, `PREMIUMS` and `TERRITORY_FACTORS` have none, so five of the seven PAS tables cannot be delta-extracted reliably, and deletes are invisible on all of them. The product (IBM Data Replication for Db2 z/OS, or an equivalent the DBA team already licenses) is an ARB/procurement question.
5. **Portable SQL:** repositories use SQL that runs on both DB2 for z/OS and PostgreSQL during the transition, verified by integration tests against both (PostgreSQL via Testcontainers; DB2 via a Db2 container as an approximation, with z/OS-specific differences listed by AB-521).
6. **`ACMEINS.CLAIMS` is not migrated.** The service reads claims data through the interface agreed with the Claims team ([FU-17](../modernization/open-questions.md)).

**Why.** PostgreSQL is the platform's standard managed relational database, supports the `DECIMAL`/`CHAR` types needed for exact parity, and carries no extra licence. Keeping DB2 as the only writable store until sign-off satisfies DR-2 and avoids the classic strangler failure of two databases drifting apart. Reverse CDC keeps the rollback path real (AB-529 acceptance criterion).

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| PostgreSQL 16 (managed), single system of record, DB2 to PostgreSQL CDC, reverse CDC after cutover | **Chosen** | Standard platform database, exact decimal types, one writer at a time, rehearsable rollback |
| Db2 LUW on the platform | Rejected | Smallest SQL change, but keeps IBM licensing and DBA specialism; no benefit once off z/OS |
| Oracle or SQL Server | Rejected | Licence cost; not the platform default (the Broker Portal's SQL Server is a consumer, not a reason to host PAS there) |
| Keep DB2 for z/OS as the permanent database | Rejected | Leaves MIPS cost and the mainframe in place (DR-7) |
| Application dual writes to DB2 and PostgreSQL | Rejected | No atomicity across the two databases; silent drift on partial failure |
| Bi-directional sync while both sides write | Rejected | Conflict resolution on the same rows; violates one-writer rule |
| Scheduled delta extract instead of CDC | Rejected | Five of the seven PAS tables have no change timestamp (two of them only an insert timestamp); deletes are invisible |
| Big-bang data migration at cutover without prior replication | Rejected | No reconciliation history or parallel run before go-live (AB-529) |

### D4: Messaging: Kafka domain events with a transactional outbox and an MQ bridge

**Decision.** Replace the CICS TD/TS queue writes with **domain events on the platform's managed Apache Kafka**: `pas.policy.created`, `pas.policy.renewed`, `pas.policy.endorsed`, `pas.underwriting.requested`, `pas.underwriting.decided`, each with a versioned JSON Schema in the schema registry (AB-528 defines the payloads). Events are written to an **outbox table** in the same database transaction as the business change and relayed to Kafka, giving at-least-once delivery with an idempotency key (policy number + event id). This fixes the silently ignored queue writes and the shared TD queue ([Q-13](../modernization/quirk-register.md#q-13)). Consumers that stay on IBM MQ during the epic (FileNet I-6 and the underwriting work queue I-4/I-5, which are out of scope to re-platform) get a **bridge** (Kafka Connect IBM MQ sink) that writes the same payload they receive today until each owner moves to the Kafka topic. The endorsement audit trail becomes a durable append-only audit table plus the `pas.policy.endorsed` event ([Q-23](../modernization/quirk-register.md#q-23), retention set by Compliance, [FU-09](../modernization/open-questions.md)). Failed deliveries go to a dead-letter topic with replay. **Ordering constraint:** the outbox relay, the topics and the MQ bridge must be deployed and tested with FileNet and the underwriting work queue *before* any event-producing capability (C-4 to C-7) leaves `LEGACY`; until then those capabilities stay on the CICS path, which keeps writing the TD queue.

**Where the outbox and audit tables live.** C-4 to C-7 can switch to `NEW` before AB-529 while DB2 is still the system of record (D3), and a CDC-fed PostgreSQL table cannot join a DB2 unit of work. So:
- *Transition:* the outbox (DS-04) and the audit table (DS-05) are **new DB2 tables** `PASAPP.EVENT_OUTBOX` and `PASAPP.POLICY_AUDIT` in the same DB2 subsystem as `ACMEINS`, written in the same DB2 unit of work as the business change. They live in a separate schema so the legacy `ACMEINS` schema and its frozen DDL in `sql/` are untouched, and so new objects are never confused with the legacy contract. In PostgreSQL the same tables live in schema `pasapp`; schema `acmeins` holds only the legacy tables.
- *DDL ownership:* AB-525 (the first ticket that writes) delivers the DB2 and PostgreSQL DDL for both tables as Flyway migrations in its own range (V50-V59), in a DB2 location and a PostgreSQL location of `pas-persistence`, never under `sql/`. The DBA team reviews and applies the DB2 migrations through its change process. AB-526 and AB-528 only add to these tables, in their own ranges.
- *Grants:* the transitional DB2 account (PLT-02) gets INSERT, SELECT and DELETE on `PASAPP.EVENT_OUTBOX` (service and relay) and INSERT and SELECT only on `PASAPP.POLICY_AUDIT` (append-only).
- *Cutover and rollback:* the outbox is drained to empty during the cutover write freeze. `PASAPP.POLICY_AUDIT` is replicated to `pasapp.policy_audit` by the CDC pipeline (MSG-02) like the `ACMEINS` tables, and back by reverse CDC during the rollback window.

**Why.** Several independent consumers (FileNet, underwriting work queue, future Broker Portal and analytics) need the same facts; Kafka gives fan-out, retention and replay, and the same platform carries the CDC stream (D3). The outbox removes the dual-write problem between database and broker. The MQ bridge keeps FileNet and the UW queue unchanged (DR-6).

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| Kafka + transactional outbox + MQ bridge for legacy consumers | **Chosen** | Fan-out and replay, no DB/broker dual write, legacy consumers unchanged during transition |
| IBM MQ on distributed platforms (JMS from Java) | Rejected | Point-to-point queues, no replay; keeps IBM licensing; acceptable fallback if the platform has no managed Kafka |
| RabbitMQ | Rejected | Not a platform standard here; weaker retention/replay |
| Cloud provider queue/topic service | Rejected | Ties PAS to one provider; no MQ bridge story for FileNet |
| Publish directly to the broker inside the request (no outbox) | Rejected | A crash between commit and publish loses the event, which is the Q-13 failure mode again |

### D5: Authentication and authorisation for the new APIs

**Decision.**
- **Authentication:** OAuth 2.0 / OpenID Connect against the enterprise identity provider. Both applications are Spring Security resource servers that validate JWT signature, issuer, audience and expiry on **every** endpoint; deny by default. Systems use the client-credentials grant; user-facing channels (Broker Portal, any new CSR/underwriter UI) use authorization code with PKCE in their own front ends and pass the user's token.
- **Coarse authorisation:** scopes per capability: `pas.policy.read`, `pas.policy.write`, `pas.underwriting.decide`, `pas.underwriting.override`, `pas.batch.operate`.
- **Object-level authorisation:** every policy read and write is filtered by the caller's entitlement (agent code, branch, book of business) from token claims, enforced in the service layer, not only at the gateway. Tests must prove that unauthenticated and cross-entitlement requests are rejected (AB-524, AB-525, AB-526; related AB-364, AB-365).
- **PII minimisation:** responses include policyholder PII only for scopes that need it. Direct PII (name, address, date of birth, SSN last 4, tax id, credit score) never appears in logs or events. Events, the outbox and the bridged 125-byte `POLICY-RECORD` do carry pseudonymous personal identifiers (`POLICY-HOLDER-ID`, `POLICY-AGENT-CODE`), so they are treated as personal data and go through Data governance review (DS-04, MSG-01, INT-01).
- **Management endpoints:** only health, readiness, liveness and metrics, on a separate management port that is not routed through ingress; API documentation UI disabled in production.
- **Audit identity:** the authenticated principal of every write is recorded in the new audit table (DS-05, delivered by AB-525 so it exists before the first write capability moves); the existing 8-character audit columns keep an 8-character id ([Q-518-1](../modernization/quirk-register.md#q-518-1)).
- **Compat gate:** turning on authentication changes the facade contract's status codes; AB-524 approves that change explicitly in `compat/allowed-changes.json` and provides `COMPAT_AUTH_HEADER` (see compat/README.md).

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| OAuth 2.0 / OIDC JWTs, scopes plus object-level checks in the service | **Chosen** | Enterprise standard, carries user identity end to end, testable entitlement rules |
| Network segmentation only (status quo) | Rejected | The current finding (Q-36); fails DR-3 |
| Static API keys | Rejected | No user identity, hard to rotate, no entitlement claims |
| Mutual TLS only | Rejected | Proves the calling system, not the user; kept as an optional transport layer in the service mesh |
| Authentication at the API gateway only | Rejected | Single control; the service must still enforce object-level rules |

### D6: Secure file transfer for the Claims and Actuarial feeds

**Decision.** Replace the z/OS FTP client steps with **SFTP through the enterprise managed file transfer (MFT) service** (fallback: direct SFTP from `pas-batch` if no MFT service exists). Key-based authentication only; keys and host fingerprints live in the secrets manager and are rotated. Each delivery ships with a SHA-256 checksum manifest, and the job only reports success after delivery confirmation; failures alert on-call (replacing the operator notification I-10). The file layouts (LRECL 200 and 150 for Claims, the exposure file for Actuarial) stay byte-for-byte identical unless the consumer signs off a change ([FU-14](../modernization/open-questions.md), [FU-15](../modernization/open-questions.md)). Files at rest in staging are encrypted and purged after confirmed delivery.

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| SFTP via the enterprise MFT service, checksums and delivery confirmation | **Chosen** | Encrypted in transit, central audit, consumers keep a file-drop model |
| FTPS (FTP over TLS) | Rejected | Firewall-unfriendly (separate data channel); weaker tooling on the consumer side |
| HTTPS API push to Claims/Actuarial | Rejected | Consumers are file-based batch imports (VB6, SAS); would force them to re-platform |
| Object storage drop with signed URLs | Rejected for now | Viable later; consumers would need new ingestion code |
| PGP-encrypt files and keep FTP | Rejected | Still a plaintext protocol with credentials in clear (Q-32) |

### D7: Strangler routing and the rule for when a capability has moved

**Decision.** Migrate one **capability** at a time, routed per capability, not per user or per record.

| Capability | Legacy today | Routing switch | Ticket |
|---|---|---|---|
| C-1 Rating | PREMBAT 3200-CALCULATE-PREMIUM | library call inside C-2 | AB-522 |
| C-2 Premium batch | PREMIUM-BATCH (CA-7 PAS0050) | scheduler: hold the CA-7 job, enable the platform job | AB-523 |
| C-3 Policy inquiry | PQRY + `java-facade` | API gateway route `/api/v1/policies/**` | AB-524 |
| C-4 New business | PNEW | channel calls the API; CICS transaction disabled | AB-525 (switch to `NEW` gated on the AB-528 event path) |
| C-5 Underwriting decision | PUWR | channel calls the API; CICS transaction disabled | AB-525 (switch to `NEW` gated on the AB-528 event path) |
| C-6 Renewal | PRWL | channel calls the API; CICS transaction disabled | AB-526 (switch to `NEW` gated on the AB-528 event path) |
| C-7 Endorsement | PEND | channel calls the API; CICS transaction disabled | AB-526 (switch to `NEW` gated on the AB-528 event path) |
| C-8 Claims extract | DAILY-EXTRACT (CA-7 PAS0100) | scheduler switch | AB-527 |
| C-9 Actuarial feed | MONTHLY-EXPOSURE (CA-7 PAS0200) | scheduler switch | AB-527 |
| C-10 Events and Broker Portal access | TD queue writes, ODBC linked server | consumers move to events / inquiry API | AB-528 |
| C-11 System of record | DB2 | `pas-persistence` adapter switch | AB-529, AB-530 |

Each capability has a routing state in configuration, owned by the release manager: `LEGACY` → `SHADOW` (Java runs on the same inputs without side effects and outputs are diffed) → `NEW` (Java serves; legacy path disabled but kept for rollback) → `RETIRED` (legacy removed in AB-530). Online capabilities are entered through the API, so the routing point is the API gateway for HTTP consumers and the channel for 3270 users; batch capabilities are routed at the scheduler. **Only one path may perform writes for a capability at any time.**

**Definition of moved.** A capability may go from `SHADOW` to `NEW` only when all of these hold, recorded in the capability's ticket:
1. **Parity:** the characterization harness (AB-520, `make parity`) passes at cent level for the capability's fixtures; every difference is a quirk whose decision is `fix` and signed off in the quirk register.
2. **Downstream delivery:** every message and file the legacy path produces still reaches its consumer through the new path (for C-4 to C-7: the outbox, Kafka topics and MQ bridge from D4 are live and verified with FileNet and the underwriting work queue).
3. **Contracts:** `make compat` passes; any contract change is listed in `compat/allowed-changes.json` with the ticket and a consumer sign-off.
4. **Shadow evidence:** online capabilities: at least 10 business days of shadow traffic with zero unexplained differences; batch and extracts: at least 5 consecutive byte-identical runs (AB-527), and the premium batch within the 30-minute target on a full-book volume (AB-523).
5. **Security:** authentication and object-level authorisation tests pass; every write the capability performs is recorded with its principal in the audit table (DS-05); Security has reviewed any new component it touches (see the component register below).
6. **Operability:** dashboards, alerts and a runbook exist; the switch back to `LEGACY` has been rehearsed.
7. **Sign-off:** the capability's business owner and the integration owners affected (I-1..I-10) approve.

A capability is **retired** only after the parallel run and cutover (AB-529, AB-530) and once its legacy path has had no traffic for an agreed period.

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| Per-capability routing with LEGACY/SHADOW/NEW/RETIRED states and an explicit definition of moved | **Chosen** | Small reversible steps; one writer per capability; objective exit criteria |
| Big-bang cutover of all capabilities | Rejected | No way to prove parity incrementally; one huge rollback |
| Per-record or per-customer routing (some policies on Java, some on COBOL) | Rejected | Two writers for the same tables and sequence; renewal and batch would need to know which side owns each policy |
| Percentage-based canary of writes | Rejected | Same two-writer problem; acceptable only for read capabilities (C-3) |

### D8: Observability and operations

**Decision.** Structured JSON logs with correlation ids and no PII; metrics via Micrometer in Prometheus format; distributed tracing via OpenTelemetry; dashboards and alerts on the platform observability stack. Batch jobs publish a run summary (read, written, skipped, errors) and page on failure, replacing the TSO SEND to PASADMIN (I-10, [Q-28](../modernization/quirk-register.md#q-28)). Baseline delivered in AB-519; per-capability alerts and runbooks with each capability ticket.

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| Platform observability stack (Micrometer, OpenTelemetry, JSON logs) | **Chosen** | Platform standard; supports the shadow-run diff dashboards |
| Application log files on the container (as the facade does today) | Rejected | Lost on pod restart; no alerting |

### D9: Personal data handling in the target store

**Decision.** `POLICY_HOLDERS` PII (`DATE_OF_BIRTH`, `SSN_LAST4`, `TAX_ID`, `CREDIT_SCORE`) is protected in the target with storage encryption plus column-level encryption or tokenisation for `SSN_LAST4` and `TAX_ID`, with keys in the platform key management service. Non-production environments only use masked or synthetic data; parity fixtures and golden files (AB-520) contain no production PII. The exact scheme is agreed with Security and signed off in AB-521. CDC streams and shadow schemas inherit the same classification as the source tables.

**Alternatives considered**

| Option | Outcome | Reason |
|---|---|---|
| Storage encryption + column-level encryption/tokenisation for the most sensitive fields, masked data outside production | **Chosen** | Defence in depth for regulated data; keeps exact values available where the business needs them |
| Storage encryption only | Rejected | DBAs, backups and replicas see clear values |
| Drop or hash the fields | Rejected | Underwriting uses the credit score; servicing needs DOB and tax id |

## Target architecture overview

```
 Broker Portal ─┐   Facade consumers (I-8) ─┐    CSR / UW channel ─┐
                ▼                           ▼                      ▼
        ┌────────────────── API gateway (OIDC token check, per-capability routes) ─────────────┐
        │  LEGACY routes ──► java-facade / CICS (until moved)    NEW routes ──► pas-policy-service
        └───────────────────────────────────────────────────────────────────────────────────────┘
                                         │ JWT, scopes, object-level checks
     ┌───────────────────────────────────▼──────────────┐       ┌──────────────── pas-batch ─────────────┐
     │ pas-policy-service (Spring Boot 3, Java 21)      │       │ Spring Batch 5: premium run, daily     │
     │ inquiry · new business · underwriting · renewal  │       │ Claims extract, monthly exposure feed  │
     │ endorsement   [pas-rating] [pas-underwriting]    │       │ [pas-rating]   run summary + alerts    │
     └──────┬────────────────────────┬──────────────────┘       └───────┬────────────────────────┬──────┘
            │ pas-persistence        │ outbox relay                     │ pas-persistence        │ SFTP via MFT
            ▼                        ▼                                  ▼                        ▼
   Transition: DB2 ACMEINS + PASAPP ──CDC──► PostgreSQL acmeins + pasapp (+ acmeins_shadow)            Claims (I-1), Actuarial (I-2)
   After cutover: PostgreSQL acmeins ──reverse CDC──► DB2 (rollback window)
                                     Kafka topics pas.* ──► MQ bridge ──► FileNet (I-6), UW work queue (I-4/I-5)
                                                      └──► future subscribers
   Claims data (I-7): agreed Claims-team interface, not a direct table read
```

## New components and data stores

Everything below is new to the PAS estate and needs Security and Data governance review before the ticket that introduces it ships. The register is maintained in [`0001-components.json`](0001-components.json); the table is generated by `python3 tools/adr/check_adr.py --write` and checked by `make adr`.

<!-- BEGIN GENERATED: components -->
| ID | Component | Kind | Decision | Purpose | Data held | Classification | PII | Encryption | Retention | Owner | Introduced by | Security review | Data governance review |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SVC-01 | pas-policy-service | service | D1 | Spring Boot 3 / Java 21 API for inquiry, new business, underwriting, renewal and endorsement | Reads and writes all ACMEINS PAS tables, including POLICY_HOLDERS PII | Restricted | yes | TLS in transit; no local persistence | n/a (stateless) | PAS engineering team | AB-519 | pending | pending |
| SVC-02 | pas-batch | batch | D1 | Spring Batch 5 jobs: premium run, daily Claims extract, monthly exposure feed | Reads policies and coverages, writes PREMIUMS and extract files | Restricted | yes | TLS in transit; staging files encrypted at rest | n/a (stateless); staging files purged after confirmed delivery | PAS engineering team | AB-519 | pending | pending |
| DS-01 | PostgreSQL schema acmeins (target system of record) | data-store | D3 | Target database for the ACMEINS tables; CDC replica of DB2 until cutover, system of record after | POLICIES, COVERAGES, PREMIUMS, ENDORSEMENTS, UNDERWRITING_DECISIONS, POLICY_HOLDERS, TERRITORY_FACTORS | Restricted | yes | Storage encryption; column-level encryption or tokenisation for SSN_LAST4 and TAX_ID (D9); TLS connections | Per the PAS records retention policy (to be confirmed by Data governance) | PAS engineering team + DBA team | AB-521 | pending | pending |
| DS-02 | PostgreSQL schema acmeins_shadow (parallel-run outputs and reconciliation) | data-store | D3 | Outputs of shadow batch and extract runs and daily reconciliation results | Shadow PREMIUMS rows, extract images, row counts, totals and record hashes | Restricted | yes | Storage encryption; TLS connections | Until cutover sign-off plus the rollback window, then dropped | PAS engineering team | AB-529 | pending | pending |
| DS-03 | Spring Batch job repository (schema pas_batch) | data-store | D2 | Job and step execution metadata for restart and run summaries | Job parameters, step counts, exit codes; no business data | Internal | no | Storage encryption; TLS connections | 13 months of run history | PAS engineering team | AB-523 | pending | pending |
| DS-04 | Transactional outbox table (DB2 PASAPP.EVENT_OUTBOX during the transition, PostgreSQL pasapp.event_outbox after cutover) | data-store | D4 | Events written in the business transaction and relayed to Kafka | Event envelopes with policy number, status fields and pseudonymous identifiers (policyholder id, agent code); no direct PII | Confidential | yes | Storage encryption; TLS connections | Deleted 7 days after successful relay | PAS engineering team | AB-525 | pending | pending |
| DS-05 | Policy audit table (DB2 PASAPP.POLICY_AUDIT during the transition, PostgreSQL pasapp.policy_audit after cutover) | data-store | D5 | Durable, append-only audit of every write with the authenticated principal; AB-525 creates it for new business and underwriting, AB-526 adds renewal and endorsement (Q-23) | Principal, action, policy number, before/after values of changed fields, including policyholder PII when POLICY_HOLDERS fields change | Restricted | yes | Storage encryption; column-level encryption/tokenisation of PII values as in D9; TLS connections; append-only grants | Set by Compliance (FU-09) | PAS engineering team + Compliance | AB-525 | pending | pending |
| DS-06 | Class-factor reference table (PASAPP.CLASS_FACTORS / pasapp.class_factors) | data-store | D3 | Effective-dated rating factor per coverage class code for table-driven rating (AB-522) | CLASS_CODE, EFFECTIVE_DATE, RATING_FACTOR | Internal | no | Storage encryption; TLS connections | Kept while referenced by premiums (to be confirmed by Data governance) | PAS engineering team + Actuarial / Pricing (values) + DBA team | AB-521 | pending | pending |
| DS-07 | Extract delivery receipts and staging (pasapp.extract_delivery, pas-batch staging volume) | data-store | D6 | One receipt per delivered or failed feed file (name, business date, counts, SHA-256, redacted destination, status); staged files wait here until delivery is confirmed | Receipts: metadata only, no business data. Staging volume: the extract files (policy, coverage, exposure) for the duration of one run | Confidential | no | Storage encryption; staging directory and files owner-only (0700/0600); TLS connections | Receipts 13 months; staged files purged after confirmed delivery, kept for investigation after a failed one | PAS engineering team | AB-527 | pending | pending |
| DS-08 | Rate reference tables (PASAPP.BASE_RATES and PASAPP.RATING_PARAMETERS / pasapp.base_rates and pasapp.rating_parameters) | data-store | D3 | Effective-dated base premium per policy type, WHEN OTHER base, premium tax rate and flat surcharge read by the shared rating component (AB-522) | POLICY_TYPE, PARAMETER_CODE, EFFECTIVE_DATE, BASE_RATE, PARAMETER_VALUE | Internal | no | Storage encryption; TLS connections | Kept while referenced by premiums (to be confirmed by Data governance) | PAS engineering team + Actuarial / Pricing (values) + DBA team | AB-522 | pending | pending |
| MSG-01 | Kafka topics pas.policy.* and pas.underwriting.* with schema registry subjects | messaging | D4 | Domain events: policy created, renewed, endorsed; underwriting requested, decided; dead-letter topic | Event payloads with policy number, status, premium, risk score and pseudonymous identifiers (policyholder id, agent code); no direct PII | Confidential | yes | TLS in transit; broker storage encryption; ACLs per topic | 7 days on business topics, 30 days on the dead-letter topic | PAS engineering team + Platform team | AB-528 | pending | pending |
| MSG-02 | CDC pipeline DB2 to PostgreSQL (and reverse after cutover) | messaging | D3 | Log-based replication of ACMEINS for reconciliation, parallel run and rollback | Full row images of all ACMEINS PAS tables and PASAPP.POLICY_AUDIT, including POLICY_HOLDERS PII | Restricted | yes | TLS in transit; encrypted staging; access limited to the replication service account | Change staging kept 7 days; pipeline removed after the rollback window | DBA team | AB-529 | pending | pending |
| INT-01 | Kafka to IBM MQ bridge | integration | D4 | Delivers events to FileNet and the underwriting work queue in today's payload until the owners move to Kafka | 125-byte POLICY-RECORD payloads, including POLICY-HOLDER-ID and POLICY-AGENT-CODE (pseudonymous personal identifiers) | Confidential | yes | TLS to the queue manager | n/a (pass-through) | Platform team + MQ admins | AB-528 | pending | pending |
| INT-02 | SFTP / managed file transfer flows to Claims and Actuarial | integration | D6 | Encrypted delivery of the daily extract and monthly exposure files with checksum manifests | Policy and coverage extracts and exposure aggregates | Confidential | no | SSH transport; key-based authentication only; server host keys pinned in a known_hosts file; keys and known_hosts mounted from the secrets manager | Receipts in pasapp.extract_delivery (DS-07); MFT keeps its own receipts per the MFT policy; local payload purged after readback confirmation | PAS engineering team + Security | AB-527 | pending | pending |
| INT-03 | Claims history read interface | integration | D3 | Open-claim counts and 5-year incurred totals per policy for renewal and underwriting | Claim counts and incurred amounts by policy number | Confidential | no | TLS; OAuth client credentials | n/a (Claims team owns the data) | Claims team | AB-525 | pending | pending |
| SEC-01 | Identity provider client registrations, scopes and entitlement claims | security | D5 | OAuth clients and scopes pas.policy.read/write, pas.policyholder.read, pas.underwriting.decide/override, pas.batch.operate, pas.ops.read; entitlement claims pas_book/pas_agents/pas_branches (AB-524) | Client ids, scope grants, user entitlement attributes | Confidential | no | Managed by the IdP | Managed by the IdP | Security (IAM) | AB-524 | pending | n/a |
| SEC-02 | Secrets manager and KMS entries | security | D2 | Database credentials, SFTP keys, Kafka credentials, column-encryption keys | Credentials and keys | Restricted | no | Managed by the secrets manager / KMS; rotation policy per Security | Per the rotation policy | Security + Platform team | AB-519 | pending | pending |
| PLT-01 | API gateway routes /api/v1/policies/** and new PAS APIs | platform | D7 | Per-capability routing between legacy and new paths; token pre-check | Request metadata and access logs (no bodies) | Internal | no | TLS termination with re-encryption to the service | Access logs per the platform logging policy | Platform team | AB-524 | pending | n/a |
| PLT-02 | DB2 service account and grants for pas-policy-service and pas-batch (transition only) | platform | D3 | JDBC access to ACMEINS and the new PASAPP schema while DB2 is the system of record | Grants on ACMEINS PAS tables and POLICY_SEQ; INSERT, SELECT, DELETE on PASAPP.EVENT_OUTBOX; INSERT and SELECT only on PASAPP.POLICY_AUDIT | Restricted | yes | TLS to DB2 (DRDA over TLS) | Revoked after the rollback window | DBA team | AB-525 | pending | pending |
<!-- END GENERATED: components -->

## Consequences

Positive:
- One codebase, two deployables, one schema: a small team can own it, and rating parity is guaranteed by sharing one component between batch and online paths.
- Every capability move is reversible until cutover, and "moved" has objective exit criteria.
- The epic's security criteria (authenticated APIs, no clear-text transfer, no secret defaults, durable audit) are architectural rules, not per-ticket choices.

Negative / costs:
- During the transition the new service needs DB2 connectivity (JDBC driver, a dedicated DB2 user and grants), two new DB2 tables in schema `PASAPP` (outbox and audit, applied by the DBA team), and SQL must stay portable between DB2 and PostgreSQL.
- New platform dependencies: managed PostgreSQL, Kafka with schema registry and Kafka Connect, the MFT service, the IdP, and a CDC product licence.
- The MQ bridge and reverse CDC are temporary components that must be decommissioned explicitly (AB-528, AB-530).
- The 8-character audit columns cannot hold a full principal, so a separate audit table is needed ([Q-518-1](../modernization/quirk-register.md#q-518-1)).

Rules every later ticket follows:
- Target Java 21 / Spring Boot 3 modules in the layout of D1; no new code in `java-facade/`, `cobol/`, `jcl/` or `sql/`.
- Flyway migrations only in the ticket's allocated version range.
- No direct reads of `ACMEINS.CLAIMS` from new code.
- No broker publish outside the outbox; no file transfer outside SFTP/MFT; no endpoint without authentication.
- A new component or data store is added to `0001-components.json` (or a superseding ADR) in the same PR.

## Risks

| Risk | Mitigation | Owner |
|---|---|---|
| No managed Kafka on the platform | Fall back to IBM MQ on distributed platforms behind the same outbox (D4 alternative); ARB decision A-4 | Platform team |
| CDC product not licensed for Db2 z/OS | ARB/procurement decision A-3; without CDC, the parallel run (AB-529) cannot start | DBA team |
| SQL differences between DB2 for z/OS and PostgreSQL (CHAR padding, timestamp precision, decimal arithmetic) | Integration tests on both engines; parity at cent level; AB-521 lists the differences | AB-521 |
| Entitlement claims (agent, branch, book) not available from the IdP | Entitlement lookup service or table owned by PAS; decided in AB-524 | Security |
| Consumers of the TD/MQ messages parse the 125-byte record in ways we do not know | MQ bridge keeps payload identical; FU-06 inventory before AB-528 | MQ admins / ECM |
| Repository source may lag production load modules | Verification items in FU-05 before parity baselines are frozen | Mainframe team |

## Open items and follow-ups

These need people or environments outside this repository. They are listed again as ARB asks in the [ARB submission](arb/0001-arb-submission.md).

- Confirm the standard container platform, managed PostgreSQL version, managed Kafka and MFT availability (platform team).
- Choose and license the Db2 z/OS CDC product (DBA team, procurement).
- Confirm the enterprise IdP, the token claims available for agent/branch/book entitlements, and client registration process (Security).
- Agree the PII protection scheme (D9) and audit retention (FU-09) (Security, Data governance, Compliance).
- Approve the new DB2 schema `PASAPP` for the outbox and audit tables during the transition, and the AB-525 change process for applying its DDL (DBA team, A-9).
- Confirm SFTP/MFT endpoints and key exchange with Claims and Actuarial (FU-15).
- Raise the ARB ticket and link it to AB-518 (programme manager); this run does not create Jira tickets.
