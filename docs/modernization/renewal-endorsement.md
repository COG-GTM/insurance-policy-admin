# Renewal and endorsement on pas-policy-service (AB-526)

POLRNW (PRWL) and POLEND (PEND) move to `pas-policy-service`. ADR 0001 (D1, D4, D5, D7, D9, D11)
applies. Event publishing is not part of this change: AB-528 adds the renewal, endorsement and
underwriting-request events and replaces the CICS TS queue, TDQ and MQ writes.

## Endpoints

| Endpoint | Scope | Success | Errors |
| --- | --- | --- | --- |
| `POST /api/v1/policies/{n}/renewals` `{"termExpiryDate"}` | `policy:renew` | 201 (NEW), 202 (SHADOW) | 404 unknown/unentitled, 409 term already renewed or not the current term, 422 rule rejection, 503 LEGACY route or claims source down |
| `POST /api/v1/policies/{n}/endorsements` `{"type", "effectiveDate", ...}` | `policy:endorse` | 201 (NEW), 202 (SHADOW) | 404, 422 (type, term, date or details invalid), 503 LEGACY route |

Errors are RFC 9457 problem documents with a `code` property (for example `TOO_MANY_OPEN_CLAIMS`,
`ZERO_LENGTH_TERM`, `TERM_ALREADY_RENEWED`). Object-level authorisation reuses the AB-524
`CallerEntitlements` and SQL entitlement predicate, so unentitled policies are 404.

## Renewal (POLRNW)

- Eligible: status `AC` or `EX`; UW status `DC` is rejected (Q-17).
- Window: `pas.renewal.window-days-before-expiry` (90) / `window-days-after-expiry` (30) around the
  current expiry (Q-17).
- Open claims above `pas.renewal.max-open-claims` (3) block the renewal; no claims source, or a
  failing one, returns 503 (Q-02 fail closed). The real adapter comes from AB-525; until then
  only the `local` profile has a stub (`pas.dev.claims-stub.enabled`).
- Premium: expiring premium × (1 + `pas.renewal.uplift-pct`/100), truncated (Q-06), capped at
  `pas.renewal.max-increase-pct` (15) or `pas.renewal.max-increase-pct-by-state.<ST>` for the
  policyholder's state (Q-04). Zero or overflowing premiums are rejected (Q-47).
- Term: effective = prior expiry, expiry = `plusYears(1)`, so Feb 29 renews to Feb 28 (Q-01).
- Renewal count above 999 is rejected (Q-18). UW status becomes `PN` as in COBOL.
- Idempotent per term: `pas.policy_renewals` is keyed on (policy, prior expiry); a repeat is 409.
- One transaction: policy row lock, policy update, coverage re-dating, renewal row and audit row
  commit together; any failure rolls all of it back (Q-16). A RECEIVE/validation failure stops
  processing (Q-44).

## Endorsement (POLEND)

- Only `AC` policies. `type` is required and must be CAD, CRM, LCH, ACH or CAN (Q-12).
- The date (default today, UTC) must be inside [effective, expiry]; first and last days are
  allowed. Zero-length or inverted terms and dates outside the term are 422 (Q-48).
- Pro-rata factor = days remaining / days in term (`ChronoUnit.DAYS`, exact calendar days),
  divided with `RoundingMode.DOWN` at 6 decimal places. COBOL computes `WS-PRORATA-FACTOR`
  `PIC 9V9(6)` without `ROUNDED`, so the same truncation, but from its own day arithmetic, which
  is wrong across months and years (for example a three-year term counts 96 days). The factor is
  returned for information only (Q-11).
- The premium adjustment is always 0.00 (Q-11 Preserve, Q-49); `TOTAL_PREMIUM` is rewritten with it.
- Effects applied in the same transaction (Q-13): CAD inserts a coverage, CRM cancels one, LCH
  changes a limit, ACH changes the policyholder address, CAN cancels the policy and its active
  coverages.
- The endorsement row, effect, policy update and audit row commit together (Q-14).

## Concurrency

Both operations read the policy with `SELECT … FOR UPDATE OF p` (entitlement predicate included)
inside the transaction, the PostgreSQL equivalent of POLEND's `WITH RS USE AND KEEP UPDATE LOCKS`.
Concurrent endorsements on one policy serialise, so sequence numbers and premiums never race.
AB-525's `pas.policies.row_version` was not on `devin/ab-516-v2` when this landed; once it is,
`PolicyRepository.saveLifecycle` must increment it.

## Durable audit (Q-15)

`pas.policy_audit` (V40) replaces the `ENDORSEMENT-AUDIT` TS queue: action, reference (term or
endorsement sequence), actor (JWT `sub`), premium before/after and non-PII details (ACH records
only that the address changed, not the address).

## Routing

`pas.routing.renewal` / `pas.routing.endorsement` (`PAS_ROUTE_RENEWAL`, `PAS_ROUTE_ENDORSEMENT`),
default `legacy`:

| State | Behaviour |
| --- | --- |
| `legacy` | 503 `ROUTE_NOT_SERVING` |
| `shadow` | Computes the result, writes only `pas_shadow.renewal_results` / `endorsement_results` (V41: no FKs to `pas`, no PII), returns 202; `pas` is not changed |
| `new`, `retired` | Applies the change in `pas` |

Before cutover only the sync job writes `pas` (ADR 0001 D5), so production keeps these routes at
`legacy` or `shadow` until AB-530 flips all write capabilities together.
