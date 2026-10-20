# ACMEINS.CLAIMS interface assumptions (AB-521)

`ACMEINS.CLAIMS` is owned by the Claims team and is out of scope for the PAS modernization (epic
AB-516). The target `pas` schema does **not** contain a claims table, a copy of it, or any foreign
key to it. PAS reads claims data only through an interface agreed with Claims.

**Claims team agreement: Pending: Claims team.**

## What PAS uses today

| Program | Query | Purpose |
| --- | --- | --- |
| `POLRNW` | `SELECT COUNT(*) FROM ACMEINS.CLAIMS WHERE POLICY_NUMBER = :p AND CLAIM_STATUS = 'OPEN'` | Open-claims renewal check. The result is fetched but never enforced (the ">3 open claims blocks renewal" rule was never finished). AB-526 implements it as an intentional fix. |
| `UNDWRT` | `SELECT COUNT(*), COALESCE(SUM(INCURRED_AMOUNT),0) FROM ACMEINS.CLAIMS WHERE POLICY_NUMBER = :p AND CLAIM_DATE >= CURRENT DATE - 5 YEARS` | Claim count and incurred amount over 5 years feed the risk score. |
| `DAILY-EXTRACT` (JCL) | — | PAS *sends* policy data to Claims; Claims does not read the PAS tables directly. |

## Columns PAS assumes exist

| Column | Assumed type | Used by |
| --- | --- | --- |
| `POLICY_NUMBER` | `CHAR(12)`, same value as `pas.policies.policy_number` | POLRNW, UNDWRT |
| `CLAIM_STATUS` | `CHAR`, with `'OPEN'` meaning open | POLRNW |
| `CLAIM_DATE` | `DATE` | UNDWRT |
| `INCURRED_AMOUNT` | `DECIMAL(p,2)`, money | UNDWRT |

These are inferred from the COBOL SQL only; Claims has not confirmed them.

## Assumptions the target schema makes

1. **No referential integrity.** Claims rows reference `POLICY_NUMBER` by value. PAS never deletes or
   renumbers policies, so Claims references stay valid; there is no FK in either direction.
2. **Policy numbers are stable.** The 12-character format (`POL` + 9 digits, see
   `PolicyNumbers.fromSequence`) and existing numbers do not change during migration, so claims
   joins by policy number keep working.
3. **Read-only, per policy.** PAS needs two aggregates per policy: open-claim count, and
   (count, sum of incurred) over the last 5 years from an as-of date. Nothing else.
4. **Freshness.** COBOL reads the live table. The interface should be near-real-time for
   underwriting and renewal (proposed: synchronous API; a nightly snapshot is not enough for
   renewals processed the same day a claim opens).
5. **Date semantics.** "Last 5 years" is calendar years from the evaluation date (DB2
   `CURRENT DATE - 5 YEARS`), inclusive of the boundary day.
6. **Failure behaviour.** If the interface is unavailable, PAS fails the underwriting/renewal
   request (no silent "0 claims"). To be confirmed with the business.

## Proposed interface (for agreement)

Implemented in AB-525 as `com.acme.insurance.pas.policy.claims.ClaimsHistoryPort`
(`summarize(policyNumber, asOf)` → `ClaimsSummary(openClaimCount, claimCount5y, incurredAmount5y)`),
with the semantics above pinned by the `ClaimsHistoryPortContract` test. Adapters, selected with
`pas.claims.adapter` (`PAS_CLAIMS_ADAPTER`): `unavailable` (default; every call fails, so
underwriting returns 503 instead of scoring a clean history) and `stub` (in-memory, local/test).
The Claims-owned adapter is **Pending: Claims team**.

Originally proposed: a port in pas-policy-service, implemented by an adapter that calls a Claims-owned
read API, e.g. `GET /claims/v1/policies/{policyNumber}/summary?asOf=YYYY-MM-DD` returning
`{ openClaimCount, claimCount5y, incurredAmount5y }`. Until the API exists, an adapter can read a
Claims-published DB2 view with the same three aggregates. Owner, transport and SLA: see
`open-questions.md`.
