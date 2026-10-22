# Claims table assumptions

The repository has no `ACMEINS.CLAIMS` DDL. The legacy programs reference
`POLICY_NUMBER`, `CLAIM_STATUS`, `CLAIM_DATE`, and `INCURRED_AMOUNT`, but this is not enough to
define the table's full types, keys, status domain, nullability, or retention. See
[M-01](missing-artifacts.md#m-01). AB-521 therefore does not create or migrate a claims table.

The common domain adds only the adapter boundary needed by policy and underwriting work:

- `countOpenClaims(policyNumber)` for the existing open-claim count;
- `summarizeSince(policyNumber, since)` for a count and a scale-2 incurred amount.

`ClaimsSummary` normalizes a supplied amount to scale 2 without rounding away non-zero precision.
It does not define how a claims adapter determines open statuses, handles null incurred amounts,
deduplicates claims, or interprets dates; those rules require the missing source contract.

The integration choice and freshness remain open in
[OQ-18](open-questions.md#oq-18), and the observations about the current COBOL reads are recorded
in [Q-02](quirk-register.md#q-02). Adapters and any target claims schema must wait for the Claims
team's source-of-truth and freshness decision; no guessed schema or status mapping should be
introduced in the meantime.
