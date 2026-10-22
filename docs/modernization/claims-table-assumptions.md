# Claims table assumptions

The repository has no `ACMEINS.CLAIMS` DDL. The legacy programs reference
`POLICY_NUMBER`, `CLAIM_STATUS`, `CLAIM_DATE`, and `INCURRED_AMOUNT`, but this is not enough to
define the table's full types, keys, status domain, nullability, or retention. See
[M-01](missing-artifacts.md#m-01). AB-521 therefore does not create or migrate a claims table.

The common domain adds only the adapter boundary needed by policy and underwriting work:

- `countOpenClaims(policyNumber)` for the existing open-claim count;
- `summarizeSince(policyNumber, since)` for a count and a scale-2 incurred amount.

`ClaimsSummary` normalizes a supplied amount to scale 2 without rounding away non-zero precision.
AB-525 supports a configured REST claims-history adapter and a fixture adapter for local/tests.
REST mode uses OAuth2 client credentials, maps a 404 to zero claims, retries only 5xx/I/O failures,
and fails closed with 503 when history remains unavailable. A missing REST base URL is a startup
error. No target claims schema is added.

The Claims team still needs to confirm the endpoint and the meaning/freshness of the five-year
count and incurred amount before production rollout. See [OQ-18](open-questions.md#oq-18) and the
legacy open-claim observations in [Q-02](quirk-register.md#q-02).
