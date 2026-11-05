# New business and underwriting APIs (AB-525)

Replaces CICS transactions `PNEW` (program `POLNEW`) and `PUWR` (program `UNDWRT`) with endpoints in
`pas-policy-service` (Java 21 / Spring Boot 3). Decisions follow
[ADR 0001](../adr/0001-pas-target-architecture.md) D1 (one policy service), D3 (events through a
transactional outbox), D5 (security) and D7 (Claims data through the Claims team's interface). The spec
is [`new-business-underwriting-v1.yaml`](../../pas/pas-policy-service/src/main/resources/openapi/new-business-underwriting-v1.yaml).

## 1. Endpoints

| Endpoint | Scope | Replaces |
|---|---|---|
| `POST /api/v1/policies` | `pas.policy.write` | POLNEW `3000-VALIDATE-INPUT`, `4000-GENERATE-POLICY-NUMBER`, `5000-INSERT-POLICY`, `6000-CREATE-DEFAULT-COVERAGES`, `7000-WRITE-AUDIT` |
| `POST /api/v1/policies/{policyNumber}/underwriting-decisions` | `pas.underwriting.decide` | UNDWRT `3000-CALCULATE-RISK-SCORE`, `4000-CHECK-ACCUMULATION`, `5000-MAKE-DECISION`, `6000-WRITE-DECISION`, `7000-NOTIFY` |
| `POST /api/v1/policies/{policyNumber}/underwriting-decisions/override` | `pas.underwriting.override` | Manual override (new; UNDERWRITING_DECISIONS.OVERRIDE_REASON / OVERRIDE_BY existed but no program wrote them) |
| `GET /api/v1/policies/{policyNumber}/underwriting-decisions` | `pas.policy.read` | Decision history (new) |

No token: 401. Token without the scope: 403. Object-level entitlement (`pas_book`, `pas_agents`,
`pas_branches`, as in AB-524): a new policy outside the caller's book answers 403; an existing policy
outside it answers 404, exactly like a missing policy. Covered by `NewBusinessApiIT`.

## 2. Create policy (POLNEW)

Validation runs in POLNEW order and the response `message` is the PNEW text of the **last** failed
check (POLNEW overwrites its message), while `violations` lists all of them:

| Check | Message |
|---|---|
| Policy type present | `POLICY TYPE IS REQUIRED` |
| Holder id present | `POLICYHOLDER ID IS REQUIRED` |
| Effective date not before the business date | `EFFECTIVE DATE CANNOT BE IN THE PAST` |
| Limit greater than zero (POLNEW only rejects 0, Q-525-1) | `POLICY LIMIT MUST BE GREATER THAN ZERO` |
| Holder exists | `POLICYHOLDER NOT FOUND` |

Field widths and decimal places of the DB2 columns are checked first (400). Business checks answer 422.

- **Policy number:** next value of `POLICY_SEQ` (PostgreSQL `nextval`, DB2 `NEXT VALUE FOR`), formatted
  with `pas.new-business.policy-number-format` (default `POL-%08d`, at most 12 characters, else the
  request fails rather than truncating like the COBOL `STRING`, Q-08). The production format is
  pending FU-05.
- **Row:** `POLICY_STATUS` and `UW_STATUS` `PN`, `RISK_SCORE` empty, premium stored **as entered**
  (Q-12, rating stays with PREMBAT / `pas-rating`), explicit `ISSUE_DATE` (business date) and
  `EXPIRY_DATE` (effective date + `pas.new-business.term-months`, default 12; POLNEW never set it, Q-09,
  FU-525-2), `CREATED_BY`/`UPDATED_BY` the caller's 8-character id (`pas_legacy_user`, default `PASAPI`).
- **Default coverages** come from `PASAPP.DEFAULT_COVERAGES` (V52), seeded with the POLNEW table:
  AUT `AUTL`, `AUTP`; HOM `PROP`, `LIAB`; COM `PROP`, `LIAB`, `WKCP`; LIF and HLT none. Each line copies
  the policy limit and deductible, like POLNEW.
- **Business date:** today in `pas.business-zone` (default `America/New_York`, FU-525-6).
- Policy, coverages, audit row and events are one database transaction.

## 3. Underwriting decision (UNDWRT)

`pas-underwriting` (`Underwriter`, `RiskScorer`, `UnderwritingRules`) ports the score. Thresholds are
configuration (`pas.underwriting.*`), defaulting to the legacy values:

| Factor | Points |
|---|---|
| Credit score > 0 | `(900 - score) / 2`, truncated |
| No credit score (0 or NULL, Q-525-2) | 400 |
| Risk tier `U` / `P` | +200 / -100 |
| Claims in the last 5 years > 3 / > 5 | +150 / +200 (not cumulative) |
| 5-year loss ratio (incurred / premium x 100) > 80 | +200 (full ratio kept, Q-15) |
| Coverage limit > 5,000,000 / > 10,000,000 | +100 / +200 |
| Branch active (AC) limit total > 500,000,000 | +300 |

Bands: score < 300 `AP`, 300-599 `RS` (senior underwriter), 600-799 `RM` (manager), >= 800 `DC`.
A negative score (preferred tier) is stored as 0 and a score over 999 is stored in full (Q-14). The
reason keeps the accumulation text as well as the band text (Q-16).

The decision inserts `UNDERWRITING_DECISIONS` (`UNDERWRITER_ID` `SYSTEM`), updates
`POLICIES.UW_STATUS`/`RISK_SCORE` (`UPDATED_BY` `UNDWRT`), writes the audit row and publishes
`pas.underwriting.decided`, in one transaction. DB2's `UNDERWRITING_DECISIONS` key is
(policy, date), so a second decision on the same day answers 409 there; PostgreSQL keeps both rows.

**Overrides:** `decisionCode` `AP` or `DC` and a `reason` (1-200 characters) are required. The latest
decision gets `OVERRIDE_REASON` and `OVERRIDE_BY` (the caller's 8-character id) under an optimistic
check (409 if it changed meanwhile), and `POLICIES.UW_STATUS` takes the override.

## 4. Claims history (FU-17)

UNDWRT read the Claims team's table directly. The Java service never does: the `ClaimsHistory` port
is implemented by `HttpClaimsHistory`, which calls the Claims interface proposed to the Claims team:

```
GET {PAS_CLAIMS_BASE_URL}/v1/policies/{policyNumber}/claims-summary?from=YYYY-MM-DD&to=YYYY-MM-DD
200 {"policyNumber": "...", "claimCount": 2, "totalIncurred": 1250.00}
```

OAuth 2.0 client credentials (registration `pas.claims.client-registration`) when configured; connect
and read timeouts `pas.claims.connect-timeout` / `read-timeout`. Any transport error, non-2xx answer,
missing field or other policy number is "claims unavailable": the decision answers 503 and writes
nothing. Without `PAS_CLAIMS_BASE_URL` every decision answers 503. Contract, SLA and counted claim
statuses are pending FU-525-4.

## 5. Events (replacing the TD queue writes)

| Event | Topic | Replaces | When |
|---|---|---|---|
| `PolicyCreated` | `pas.policy.created` | POLNEW `WRITEQ TD` | Policy created |
| `UnderwritingRequested` | `pas.underwriting.requested` | Referral to underwriting | Policy created (status PN) |
| `UnderwritingDecided` | `pas.underwriting.decided` | UNDWRT `WRITEQ TD` (PUWR notification, incl. RS/RM referrals) | Decision or override |

Envelope (`pas-events` `PasEvent`): `eventId` (UUID), `type`, `schemaVersion`, `occurredAt`,
`policyNumber` (also the Kafka key), `correlationId`, `payload`. Events are appended to
`PASAPP.EVENT_OUTBOX` (V50) in the business transaction; `OutboxRelay` publishes them to Kafka with
headers `eventId`, `eventType` and `schemaVersion` and marks them sent. Delivery is
at-least-once: consumers deduplicate on `eventId`. The relay is off unless
`pas.events.relay-enabled=true`; run one relay per environment (FU-525-7). Sent rows are purged after
`pas.events.retention`.

## 6. Audit

`PASAPP.POLICY_AUDIT` (V51) keeps an append-only row per write (create, decision, override) with the
before/after JSON, token subject, client id, legacy user and correlation id. PostgreSQL rejects
UPDATE and DELETE with a trigger; on DB2 the application id only gets INSERT and SELECT.

## 7. Parity

Suite `undwrt-decision` runs `UndwrtDecisionAdapter` on every fixture, including the 299/300,
599/600 and 799/800 boundaries (`make parity`). Known differences, all pending sign-off:
KD-03 (Q-14), KD-04 (Q-15 score), KD-05 (Q-16), KD-06 (Q-15 loss-ratio probe), KD-07 (Q-520-1: a
failed receive is rejected before any write). POLNEW has no harness suite; `NewBusinessApiIT` covers
it end to end against PostgreSQL and Kafka.

## 8. Configuration

| Property | Env | Default |
|---|---|---|
| `pas.new-business.policy-number-format` | `PAS_POLICY_NUMBER_FORMAT` | `POL-%08d` |
| `pas.new-business.term-months` | `PAS_POLICY_TERM_MONTHS` | `12` |
| `pas.business-zone` | `PAS_BUSINESS_ZONE` | `America/New_York` |
| `pas.claims.base-url` | `PAS_CLAIMS_BASE_URL` | none (decisions answer 503) |
| `pas.events.relay-enabled` | `PAS_EVENTS_RELAY_ENABLED` | `false` |
| `pas.events.create-topics` | `PAS_EVENTS_CREATE_TOPICS` | `false` |

## 9. Sign-offs

| Item | Owner | Status |
|---|---|---|
| Q-525-1, Q-525-2 fixes (FU-525-1) | Underwriting lead | pending |
| KD-06, KD-07 known differences | Underwriting lead + Mainframe team | pending |
| Policy number format (FU-05) and term (FU-525-2) | Mainframe team + Product Owner | pending |
| Claims interface contract (FU-525-4 / FU-17) | Claims team | pending |
| Scopes and legacy user claim (FU-525-3) | Security | pending |
| Topics, ACLs and consumer move off the TD queues (FU-525-7) | Platform + downstream consumers | pending |
