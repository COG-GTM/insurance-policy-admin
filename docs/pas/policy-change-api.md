# Renewal and endorsement API (AB-526)

Replaces CICS transactions **PRWL** (program `POLRNW`) and **PEND** (program `POLEND`) with two
endpoints in `pas-policy-service`. The contract is
`pas/pas-policy-service/src/main/resources/openapi/policy-change-v1.yaml`.

| Endpoint | Scope | Legacy | Success |
|---|---|---|---|
| `POST /api/v1/policies/{policyNumber}/renewals` | `pas.policy.renew` | PRWL / POLRNW | 200 + renewed term |
| `POST /api/v1/policies/{policyNumber}/endorsements` | `pas.policy.endorse` | PEND / POLEND | 201 + endorsement |

## Where the code lives

| Concern | Class |
|---|---|
| Renewal pricing and term dates (pure) | `pas-rating` `RenewalCalculator`, `RenewalRules`, `RenewalQuote` |
| Pro-rata factor and adjustment (pure) | `pas-rating` `ProRata` |
| Refusal reasons and legacy CICS messages | `pas-rating` `PolicyChangeError` |
| Eligibility | `pas-domain` `PolicyStatus.renewable()` (AC, EX), `endorsable()` (AC) |
| Locked read, updates, coverages re-dating | `pas-persistence` `PolicyJdbcRepository` |
| ENDORSEMENTS rows | `pas-persistence` `EndorsementJdbcRepository` |
| Durable audit | `pas-persistence` `PolicyChangeAuditJdbcRepository`, Flyway V60 |
| Transactions, authorization, HTTP | `pas-policy-service` `change.RenewalService`, `EndorsementService`, `PolicyChangeController` |
| Parity | `pas-parity` `PolrnwRenewalAdapter`, `PolendEndorsementAdapter` |

## Behaviour

**Renewal.** Active (`AC`) and expired (`EX`) policies renew. New premium = old × (1 + uplift),
capped at old × (1 + cap) when the uncapped change exceeds the cap, truncated to the cent
(`RoundingMode.DOWN`, Q-06). The new term starts on the old expiry and ends one calendar year later.
`RENEWAL_COUNT` + 1, `POLICY_STATUS` `AC`, `UW_STATUS` `PN` (Q-39, no underwriting request is sent,
as today), `UPDATED_BY` `POLRNW`, and every coverage with status `AC` gets the new dates. An optional
`expectedExpiryDate` makes retries safe: a second renewal of the same term answers 409.

**Endorsement.** Only `AC` policies. The type is required and must be `CAD`, `CRM`, `LCH`, `ACH` or
`CAN` (Q-19). The endorsement takes effect on the business date (`pas.policy-change.business-zone`).
Factor = days remaining / days in term (6 decimals, truncated). Adjustment = `annualPremiumChange`
× factor, truncated to the cent; without `annualPremiumChange` it is 0.00, which is what POLEND writes
(Q-20). The `ENDORSEMENTS` row gets `MAX(ENDORSEMENT_SEQ) + 1` and `PROCESSED_BY` `POLEND`;
`TOTAL_PREMIUM` grows by the adjustment.

**Configuration** (`pas.policy-change.*`, environment variables in `application.yml`):

| Property | Default | Quirk |
|---|---|---|
| `renewal-uplift-pct` | 5.00 | Q-05 |
| `rate-increase-cap-pct` | 15.00 (flat; state-specific caps can replace the bean later, FU-04) | Q-04 |
| `leap-day-expiry` | `FEB_28` (or `MAR_01`) | Q-01, FU-01 |
| `max-open-claims` | unset = not enforced, as POLRNW | Q-02, FU-02 |
| `business-zone` | `UTC` | |

`max-open-claims` needs an `OpenClaims` bean backed by the Claims read interface (FU-17); the service
refuses to start with a maximum and no interface, so the rule cannot be switched on half-way.

## Fixes against the legacy programs

| Quirk | Legacy | Java | Parity |
|---|---|---|---|
| Q-01 | Expiry `YYYYMMDD + 10000` gives 20250229, DB2 rejects it | Valid date, 28 Feb by default | KD-01 |
| Q-41 | Zero premium divides by zero (S0CB) | Renews at 0.00, 0% change | KD-02 |
| Q-520-1 | Failed map receive falls through and abends | 400, no read or write | KD-06 |
| Q-19 | Type never read from the screen (LOW-VALUES written) | Type required, five known codes | KD-07 |
| Q-20 | Days fields `PIC 9(03)`: after expiry +31 remaining, 1096-day terms become 96 | Real day counts, remaining clamped to 0..term | KD-08 |
| Q-07, Q-22 | Continues after a failed UPDATE/INSERT | One transaction, all or nothing | (no golden case) |
| Q-23 | Audit only in TS queue `ENDORSEMENT-AUDIT` | `PASAPP.POLICY_CHANGE_AUDIT`, same transaction | adapter reports the audit write as the TS write |

Every known difference is `sign_off: pending` (`parity/known-differences.json`).

## Locking and concurrency

POLEND reads the policy `WITH RS USE AND KEEP UPDATE LOCKS`. Both services read it
`SELECT ... FOR UPDATE` inside the transaction (pessimistic lock), after the entitlement check, so a
caller cannot lock a policy it may not see. Concurrent renewals/endorsements of one policy therefore
run one after another: no lost premium update and no duplicate `ENDORSEMENT_SEQ` (the primary key
would turn a missed lock into a failed insert, never a duplicate). `PolicyChangeApiIT` fires eight
concurrent endorsements at one policy and checks sequences 1..8, the summed premium and the audit
chain.

## Authorization

Same model as the inquiry API (AB-524, AB-364): a scope per operation, then object-level entitlement
from `pas_book` / `pas_agents` / `pas_branches`. A policy outside the entitlement answers 404 with
an empty body, identical to a missing policy, and nothing is locked or written. Denials are logged
and counted in `pas.policy-change.requests{outcome="denied"}`.

## Audit (Flyway V60)

`pasapp.policy_change_audit` (PostgreSQL) / `PASAPP.POLICY_CHANGE_AUDIT` (DB2): operation, legacy
program, endorsement sequence and type, the full token subject, client id, correlation id, status,
dates and premium before and after, renewal count, timestamp. Written in the change's transaction.
PostgreSQL refuses UPDATE, DELETE and TRUNCATE with a trigger; on DB2 the application id gets
INSERT and SELECT only. No foreign key to `POLICIES`, so the audit outlives a policy purge. ADR 0001
names `pasapp.policy_audit` (DS-05, from AB-525) as the shared audit table; AB-525 is not on the
integration branch yet, so AB-526 adds its own table and FU-526-2 tracks folding the two together.

## Sign-offs

| Item | Owner | Status |
|---|---|---|
| 29 Feb convention (KD-01, FU-01) | Product Owner | pending |
| Zero-premium renewal (KD-02, FU-20) | Product Owner + Mainframe team | pending |
| Failed screen receive (KD-06, Q-520-1) | Mainframe team + Product Owner | pending |
| Required endorsement type (KD-07, Q-19) | Mainframe team | pending |
| Pro-rata day counts and formula (KD-08, FU-08, FU-526-1) | Product Owner + Actuarial | pending |
| Audit content and retention (FU-09, FU-526-2) | Compliance | pending |
| Scopes `pas.policy.renew` / `pas.policy.endorse` in the IdP (FU-526-3) | Security + Platform | pending |
| DB2 V60 DDL review | DBA team | pending |
