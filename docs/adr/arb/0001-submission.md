# ARB submission — ADR 0001 PAS target architecture

Paste this body into the ARB ticket. Ticket: **ARB-TBD — to be created** (no ARB project is
available to the authoring team; once created, add the id to the ADR header, `docs/adr/README.md`,
the PR description and Jira AB-518).

## Architecture review

```
ADR: docs/adr/0001-pas-target-architecture.md
ARB ticket: TO BE CREATED
Triage: ARB_REQUIRED — T1, T2, T4, T5, T6, T7, T8 (T9 not yet estimated)
Summary: Strangler migration of the Policy Administration System off COBOL/CICS/DB2 z/OS onto one
  Java 21 / Spring Boot 3.5 policy service + one Spring Batch app, PostgreSQL 16, Kafka with a
  transactional outbox, OAuth2 JWT APIs and SFTP feeds; DB2 stays system of record until cutover.
Exceptions requested: allowed_services AWS::MSK (only if no approved Kafka offering);
  allowed_services AWS::Transfer (only if PAS hosts the SFTP endpoints) — 12-month expiry
Reviewers needed: Enterprise Architecture, Security, Data Governance, Platform (T8),
  Mainframe/DB2 DBA, Integration owners (Claims, Actuarial, Broker Portal, FileNet)
```

## ARB triage

Done by hand against `rules/arb-triggers.md` (the plugin's `detect_arb_triggers.py` heuristic is
not run: executing plugin scripts is blocked by the session guardrail hook, and on a docs-only change set the detector reports path-based hits
that say nothing about the architecture described). The triage below is of the **architecture the
ADR decides**, which later AB-516 tickets implement.

```
ARB triage: ARB_REQUIRED
Triggers:
- T1 New deployables: pas-policy-service (online API), pas-batch (Spring Batch app with scheduled
  premium, extract, sync, reconciliation and parallel-run jobs) — ADR D1
- T2 New data stores / classification: PostgreSQL 16 schemas pas (regulated PII from
  POLICY_HOLDERS, column-encrypted), pas_batch, pas_shadow; Kafka topic storage; SFTP drop zones — D3, D4, D6, D8
- T4 New cross-domain contracts: Kafka topics pas.policy.events.v1, pas.underwriting.events.v1,
  pas.document.requests.v1; Broker Portal moves from DB2 linked server to the API; legacy-queue
  bridge to FileNet; new Claims/Actuarial delivery protocol (checksum + .done) — D6, D8, D9
- T5 Possible infra outside policy: managed Kafka (AWS::MSK) and managed SFTP (AWS::Transfer) are not
  in allowed_services — exceptions requested conditionally
- T6 AuthN/AuthZ and network boundaries: new OAuth2 resource server and IdP audience/clients/claims;
  legacy facade restricted to gateway-only ingress; new platform → DB2 and platform → MQ network paths — D5, D6, D7
- T7 Runtime/language: COBOL/CICS → Java 21; Java 8 / Boot 1.5 → Java 21 / Boot 3.5 — D2
- T8 Spans several teams: mainframe ops, DB2 DBA, platform, identity, Claims, Actuarial, Broker
  Portal, document management
Not triggered: T3 (no new SaaS vendor; IBM MQ and the IdP already exist — a managed file-transfer
  product was considered and rejected)
Unknown: T9 cost — estimate required before the ARB meeting
Required next steps: ADR (done), ARB ticket, cost estimate, NFR owner confirmations
```

## What the ARB is asked to approve

| Id | Decision |
| --- | --- |
| D1 | One policy service (inquiry, lifecycle, underwriting, events) + one batch app; shared `pas-common` / `pas-rating` libraries; no per-transaction services |
| D2 | Java 21, Spring Boot 3.5.x pinned, containers on the standard platform |
| D3 | PostgreSQL 16 (managed, multi-AZ, CMK) over Db2 LUW / Oracle |
| D4 | Schemas `pas` / `pas_batch` / `pas_shadow`; all `pas` DDL in `pas-policy-service`; never edit merged migrations; per-ticket version ranges; no FKs from `pas_shadow`; grants enforce that only the sync job writes `pas` before cutover |
| D5 | Bulk load + watermark delta sync from DB2; reconciliation; `pas_shadow` parallel run; DB2 system of record until cutover; writes flip together; reverse sync for the rollback window |
| D6 | Kafka (KRaft) + transactional outbox, ordered per policy, idempotent consumers; envelope with `policyVersion`; no PII in events; bridge rebuilds the legacy 125-byte `POLICY-RECORD` (IBM037) for unchanged consumers |
| D7 | OAuth2 JWT resource server; scopes `policy:read`, `policy:write`, `underwriting:decide`, `batch:operate`; claims `agent_id`, `branch_codes`, `book`; SQL-level object authorization (404 when unentitled); facade gateway-only until retired; PII column encryption + blind index, masking, never logged |
| D8 | SFTP with key auth, pinned host key, SHA-256 + `.done`; no plain FTP |
| D9 | Route flags per capability in `pas-platform/routing/route-flags.json`; "moved" = parity green + parallel run clean + owners signed off (+ operational readiness) |
| D10 | Structured JSON logs, Micrometer/Prometheus, probes, correlation ids; never log PII |
| D11 | BigDecimal money, COBOL truncation/rounding rules, quirk register Q-ids, byte-exact legacy layouts |

## New components and data stores (security and data-governance review)

- `pas-policy-service`, `pas-batch` (new deployables)
- PostgreSQL 16 cluster with schemas `pas` (regulated PII: `DATE_OF_BIRTH`, `SSN_LAST4`, `TAX_ID`,
  `CREDIT_SCORE` — AES-256-GCM column encryption + HMAC blind index, masked, never logged),
  `pas_batch` (no PII), `pas_shadow` (transitional, no PII, no FKs to `pas`)
- Kafka cluster and topics `pas.policy.events.v1`, `pas.underwriting.events.v1`,
  `pas.document.requests.v1` (+ consumer dead-letter topics); no PII by contract
- SFTP endpoints for Claims and Actuarial
- Secrets store entries (DB, PII keys, SFTP keys/known_hosts, Kafka, DB2 sync, MQ bridge)
- JWT issuer configuration: audience `pas-api`, client registrations, entitlement claims
- New network flows: platform → DB2 (sync, reverse sync), platform → legacy queues (bridge),
  gateway → facade only

## NFRs for confirmation

- Premium batch full book **< 30 min** (today ~4 h).
- Rollback **RPO 0 committed transactions**, **RTO ≤ 4 h** — proposed, business owner to confirm.
- Availability SLO, latency, peak load, sync lag, retention, residency, cost — **TBD**, listed as
  open questions in the ADR.

## Questions for the ARB

1. Is there an approved Kafka offering on the standard platform, or is the MSK exception granted?
2. Who hosts the SFTP endpoints; is payload encryption required for customer-data files?
3. Acceptable length of the post-cutover rollback window (drives how long reverse sync and DB2 run).
4. Confirmation that watermark-based sync with reconciliation is acceptable versus licensed
   log-based CDC on DB2 z/OS.

## Merge gate

The ADR PR may merge into the epic integration branch for implementation to proceed, but nothing
from AB-516 merges to `main` until this ARB ticket is approved. Only the ARB changes the ADR status
from `Proposed`.
