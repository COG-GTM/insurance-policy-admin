# Claims and Actuarial extract layouts (AB-527)

The layouts below are explicit v1 reconstructions for the two daily Claims files and the monthly
Actuarial CSV. `DSNTEP2` normally writes a formatted report to `SYSPRINT`; the JCL's `POLOUT`,
`COVOUT`, and `EXPOUT` DDs do not establish the delivered bytes (Q-41). The load library may contain
a site-modified copy. Production samples and confirmation of the program variant are missing
(M-13, OQ-13), so these definitions are not claimed to be proven byte parity.

Fixed record offsets below are 1-based. Numeric fields are signed DISPLAY with ASCII trailing
overpunch; `X(n)` is ISO-8859-1 text, left-justified and space-padded. `DATE` is `YYYYMMDD`.
Numeric nulls become zero; null dates become `00000000`; null text becomes spaces. Values are
right-trimmed before encoding. Text and numeric overflow reject the step rather than truncating.
The copybook codec uses ISO-8859-1 (`CopybookCharset.ASCII`).

## `daily-policy/v1` — fixed-width, LRECL 200

| Field | Source column | Offset | Width | Format |
|---|---|---:|---:|---|
| POLICY_NUMBER | `pas.policies.policy_number` | 1 | 12 | X(12) |
| POLICY_TYPE | `pas.policies.policy_type` | 13 | 3 | X(3) |
| POLICY_STATUS | `pas.policies.policy_status` | 16 | 2 | X(2) |
| EFFECTIVE_DATE | `pas.policies.effective_date` | 18 | 8 | YYYYMMDD |
| EXPIRY_DATE | `pas.policies.expiry_date` | 26 | 8 | YYYYMMDD |
| POLICYHOLDER_ID | `pas.policies.policyholder_id` | 34 | 10 | X(10) |
| AGENT_CODE | `pas.policies.agent_code` | 44 | 6 | X(6) |
| BRANCH_CODE | `pas.policies.branch_code` | 50 | 4 | X(4) |
| TOTAL_PREMIUM | `pas.policies.total_premium` | 54 | 11 | S9(9)V99, trailing overpunch |
| DEDUCTIBLE | `pas.policies.deductible` | 65 | 9 | S9(7)V99, trailing overpunch |
| COVERAGE_LIMIT | `pas.policies.coverage_limit` | 74 | 13 | S9(11)V99, trailing overpunch |
| UW_STATUS | `pas.policies.uw_status` | 87 | 2 | X(2) |
| RISK_SCORE | `pas.policies.risk_score` | 89 | 5 | S9(5), trailing overpunch |
| FILLER | — | 94 | 107 | spaces |

## `daily-coverage/v1` — fixed-width, LRECL 150

| Field | Source column | Offset | Width | Format |
|---|---|---:|---:|---|
| POLICY_NUMBER | `pas.coverages.policy_number` | 1 | 12 | X(12) |
| SEQUENCE_NUM | `pas.coverages.sequence_num` | 13 | 5 | S9(5), trailing overpunch |
| COVERAGE_TYPE | `pas.coverages.coverage_type` | 18 | 4 | X(4) |
| DESCRIPTION | `pas.coverages.description` | 22 | 40 | X(40) |
| COVERAGE_LIMIT | `pas.coverages.coverage_limit` | 62 | 13 | S9(11)V99, trailing overpunch |
| DEDUCTIBLE | `pas.coverages.deductible` | 75 | 9 | S9(7)V99, trailing overpunch |
| PREMIUM | `pas.coverages.premium` | 84 | 11 | S9(9)V99, trailing overpunch |
| EFFECTIVE_DATE | `pas.coverages.effective_date` | 95 | 8 | YYYYMMDD |
| EXPIRY_DATE | `pas.coverages.expiry_date` | 103 | 8 | YYYYMMDD |
| STATUS | `pas.coverages.status` | 111 | 2 | X(2) |
| FILLER | — | 113 | 38 | spaces |

## `monthly-exposure/v1` — CSV, maximum record length 300 bytes

There is no header row. Fields are comma-separated and quoted per RFC 4180 only when a value
contains a comma, quote, CR, or LF. Quotes inside a quoted value are doubled. Text is right-trimmed,
NULL is an empty field, sums are emitted at scale 2 using plain decimal notation, and the count is an
integer. The record separator is CRLF.

| Column | Source expression | Format |
|---|---|---|
| POLICY_TYPE | `p.policy_type` | text |
| BRANCH_CODE | `p.branch_code` | text |
| COVERAGE_TYPE | `c.coverage_type` | text |
| RATING_TERRITORY | `c.rating_territory` | text |
| CLASS_CODE | `c.class_code` | text |
| POLICY_COUNT | `COUNT(*)` | integer; counts coverage rows, not distinct policies (Q-42) |
| TOTAL_EXPOSURE | `SUM(c.coverage_limit)` | decimal, scale 2 |
| TOTAL_PREMIUM | `SUM(c.premium)` | decimal, scale 2 |
| TOTAL_DEDUCTIBLE | `SUM(c.deductible)` | decimal, scale 2 |

## Selection and ordering

* Policies preserve the Q-38 delta: status AC or PN and `last_updated >=
  businessDate.minusDays(1).atStartOfDay(zone)`. The default zone is
  `America/New_York`; there is intentionally no upper bound. The policy query orders by
  `policy_number COLLATE "C"`.
* Coverage is a full snapshot of rows with status AC and no date filter; it is not limited to the
  policies in the daily delta (Q-38).
* Exposure preserves the MONTHLY-EXPOSURE join, active-policy/active-coverage filter, grouping,
  and coverage-row `COUNT(*)` semantics (Q-42). It orders by policy type, branch, coverage type,
  rating territory, and class code using `"C"` collation. The final three keys make ties
  deterministic. PostgreSQL's ascending NULLS LAST corresponds to DB2's NULLs-high ordering.
  DB2 EBCDIC and PostgreSQL ASCII-compatible byte order can differ for mixed letter/digit codes
  (Q-46); production samples are required for parity sign-off.
* JCL names a customer extract and Broker Portal refresh, but DAILY-EXTRACT has no step that
  creates either output (Q-39, OQ-11). No customer file or customer profile attributes are added;
  the defined policy record retains its specified `POLICYHOLDER_ID`. The claimed legacy encryption
  step is also absent. Transport encryption is supplied by SFTP (Q-37).

## Record separator and versioning

All files use ISO-8859-1 and CRLF. CRLF is an assumption based on the JCL's FTP ASCII-mode transfer
to a Windows consumer; the actual receiving-side byte contract is not available (Q-41, OQ-13).
The monthly CSV filename locally includes the business date; the remote name remains fixed.

An incompatible format change requires a new descriptor version and a new recorded layout string
(for example, `daily-policy/v2`). Do not edit v1 in place. Obtain consumer sign-off, preserve the
old descriptor while old deliveries may be replayed, and compare against production samples before
routing to `new`.

Validate a sample directory with `make extract-compare EXPECTED=<production-samples>
ACTUAL=<generated-samples>`. The comparer reports names, offsets, records, fields, columns and
checksums, but never prints row values.

| Parity check | Status |
|---|---|
| Byte-for-byte vs. mainframe for 5 consecutive runs | Pending (needs production samples) |

Route configuration is coordinated here for AB-527; coordinator to reconcile with pas-common
`RouteFlags` (AB-524).
