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
outside it answers 404, exactly like a missing policy. Entitlement is checked before anything that
could reveal data: create-policy answers 403 before the holder lookup, and decisions check the book
before locking the policy row. An override additionally needs a valid `pas_legacy_user` claim (the
caller's own 8-character id, `[A-Z0-9#@$]{1,8}`); without one it answers 403 and nothing is written,
so `OVERRIDE_BY` never falls back to the service id `PASAPI`. Covered by `NewBusinessApiIT`.

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
  The expiry uses `plusMonths`, so a 29 Feb effective date expires on 28 Feb (FU-525-2).
- **Format checks (400, Q-525-3):** `policyType` must be one of AUT, HOM, COM, LIF, HLT (upper
  case; POLNEW accepted any non-blank type and then added no coverages); `totalPremium` and
  `deductible` must not be negative.
- **Default coverages** come from `PASAPP.DEFAULT_COVERAGES` (V52), seeded with the POLNEW table:
  AUT `AUTL`, `AUTP`; HOM `PROP`, `LIAB`; COM `PROP`, `LIAB`, `WKCP`; LIF and HLT none. POLNEW never
  set the coverage amounts; per the Q-10 decision they are written as an explicit 0 (limit, deductible,
  premium) with sequence numbers from 1, until rating or an endorsement fills them in.
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
A policy without a branch code skips the branch accumulation step (UNDWRT would sum the active
policies with a blank branch); policies created through the API always have the branch they were
entered with, so this only affects legacy rows (FU-525-3). The Claims interface is called before the
policy row is locked, so the lock is never held during the HTTP round trip.

**Overrides:** `decisionCode` `AP` or `DC` and a `reason` (1-200 characters) are required. The latest
decision gets `OVERRIDE_REASON` and `OVERRIDE_BY` (the caller's 8-character id, required: 403
without a valid `pas_legacy_user`) under an optimistic check on its code and override fields (409 if
it changed meanwhile, 409 if there is no decision yet), and `POLICIES.UW_STATUS` takes the override
with `UPDATED_BY` the overrider. The override replaces `DECISION_CODE` on the latest (SYSTEM) row;
`DECISION_REASON` keeps the system text and the original code survives only in the
`POLICY_AUDIT` before-image (Q-525-4, pending Underwriting sign-off). Decisions and overrides lock the policy row (`SELECT ... FOR UPDATE`), so they run one after
the other per policy and an override always applies to the latest decision.

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
`pas.events.relay-enabled=true`; run exactly one relay per environment (FU-525-7): `OutboxRelay`
does not claim rows, so a second instance would publish them twice. Add ShedLock or a leader lease
before scaling the relay out. Sent rows are purged after
`pas.events.retention`.

## 6. Audit

`PASAPP.POLICY_AUDIT` (V51) keeps an append-only row per write (create, decision, override) with the
before/after JSON, token subject, client id, legacy user and correlation id. PostgreSQL rejects
UPDATE and DELETE with a trigger; on DB2 the application id only gets INSERT and SELECT.

## 7. Parity

Suite `undwrt-decision` runs `UndwrtDecisionAdapter` on every fixture, including the 299/300,
599/600 and 799/800 boundaries (`make parity`). Known differences, all pending sign-off:
KD-03 (Q-14), KD-04 (Q-15 score), KD-05 (Q-16), KD-09 (Q-15 loss-ratio probe), KD-10 (Q-520-1: a
failed receive is rejected before any write), KD-11 (Q-14 moving the band: COBOL 325 `RS`, Java 250
`AP`). KD-06..KD-08 belong to AB-526 (merged first), so AB-525's entries were renumbered to
KD-09..KD-11; PR #102 (AB-523) renumbers its own when it merges. The events are JSON, so the WRITEQ TD effect is compared field by field from the decision
row, not byte for byte (Q-13). POLNEW has no harness suite; `NewBusinessApiIT` covers
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
| Q-525-1, Q-525-2, Q-525-3 fixes (FU-525-1) | Underwriting lead + Product Owner | pending |
| Override storage and attribution (Q-525-4) | Underwriting lead + Security | pending |
| KD-09, KD-10, KD-11 known differences (KD-11: Q-14 band change) | Underwriting lead + Mainframe team | pending |
| Policy number format (FU-05) and term (FU-525-2) | Mainframe team + Product Owner | pending |
| Claims interface contract (FU-525-4 / FU-17) | Claims team | pending |
| Scopes and legacy user claim (FU-525-3) | Security | pending |
| Topics, ACLs and consumer move off the TD queues (FU-525-7) | Platform + downstream consumers | pending |
