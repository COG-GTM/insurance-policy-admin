# Extract record layouts (AB-527)

Byte layouts of the files `pas-batch` produces in place of `jcl/DAILY-EXTRACT.jcl` (CA-7 `PAS0100`)
and `jcl/MONTHLY-EXPOSURE.jcl` (CA-7 `PAS0200`). The Java layouts are
`ExtractRecordLayouts` (built on the AB-521 `RecordLayout` codec) and `ExposureCsv`; the
independent oracle in `parity/extracts/oracle.py` re-implements the same tables.

> **Provenance.** The JCL declares only the LRECLs (200 / 150 / 300) and the SELECT column order.
> As coded, DSNTEP2 writes a formatted report to `SYSPRINT`, not to the output DDs
> ([Q-31](quirk-register.md#q-31)), and no production sample exists in the repo
> ([OQ-08](open-questions.md#oq-08)). The field widths below follow the SELECT order and the DB2
> column definitions (`sql/`, Flyway V2). They are a **reconstruction**, to be confirmed against a
> captured production file. Pending: Claims (layout sign-off). Pending: Actuarial (layout sign-off).

## Common rules

| Rule | Value |
| --- | --- |
| Character set | ISO-8859-1 (ASCII). The legacy z/OS FTP step used ASCII mode, so the consumer received EBCDIC→ASCII translated text |
| Record terminator | CRLF (`0D 0A`) after every record, including the last. Record length excludes the terminator |
| Alphanumeric `X(n)` | Left-justified, space padded; NULL → spaces; DB2 `CHAR` trailing blanks are significant only up to `n` |
| Date `9(8)` | `YYYYMMDD`; NULL → `00000000` |
| Signed numeric `S9(i)V9(s)` | Zoned DISPLAY, `i+s` bytes, implied decimal point, no separator. Sign as IBM trailing overpunch on the last digit: positive `{ABCDEFGHI` (0-9), negative `}JKLMNOPQR`. NULL → zero (`…0{`) |
| Filler | Spaces |
| Ordering | DB2 for z/OS EBCDIC (CCSID 37) collation, NULLs last, emulated in PostgreSQL by `EbcdicCollation` (e.g. digits sort after letters, lower case before upper case) |

## `policy_extract.dat` (LRECL 200)

Selection: `POLICY_STATUS IN ('AC','PN') AND LAST_UPDATED >= <businessDate> - 1 day`, ordered by
`POLICY_NUMBER` ([Q-32](quirk-register.md#q-32): the window overlaps consecutive days by ~23 h and
is preserved).

| # | Field | PIC | From | To | Len |
| --- | --- | --- | --- | --- | --- |
| 1 | POLICY_NUMBER | X(12) | 1 | 12 | 12 |
| 2 | POLICY_TYPE | X(3) | 13 | 15 | 3 |
| 3 | POLICY_STATUS | X(2) | 16 | 17 | 2 |
| 4 | EFFECTIVE_DATE | 9(8) | 18 | 25 | 8 |
| 5 | EXPIRY_DATE | 9(8) | 26 | 33 | 8 |
| 6 | POLICYHOLDER_ID | X(10) | 34 | 43 | 10 |
| 7 | AGENT_CODE | X(6) | 44 | 49 | 6 |
| 8 | BRANCH_CODE | X(4) | 50 | 53 | 4 |
| 9 | TOTAL_PREMIUM | S9(9)V99 | 54 | 64 | 11 |
| 10 | DEDUCTIBLE | S9(7)V99 | 65 | 73 | 9 |
| 11 | COVERAGE_LIMIT | S9(11)V99 | 74 | 86 | 13 |
| 12 | UW_STATUS | X(2) | 87 | 88 | 2 |
| 13 | RISK_SCORE | S9(5) | 89 | 93 | 5 |
| 14 | FILLER | X(107) | 94 | 200 | 107 |

## `coverage_extract.dat` (LRECL 150)

Selection: `STATUS = 'AC'` (full active snapshot, no date filter), ordered by
`POLICY_NUMBER, SEQUENCE_NUM`.

| # | Field | PIC | From | To | Len |
| --- | --- | --- | --- | --- | --- |
| 1 | POLICY_NUMBER | X(12) | 1 | 12 | 12 |
| 2 | SEQUENCE_NUM | S9(5) | 13 | 17 | 5 |
| 3 | COVERAGE_TYPE | X(4) | 18 | 21 | 4 |
| 4 | DESCRIPTION | X(40) | 22 | 61 | 40 |
| 5 | COVERAGE_LIMIT | S9(11)V99 | 62 | 74 | 13 |
| 6 | DEDUCTIBLE | S9(7)V99 | 75 | 83 | 9 |
| 7 | PREMIUM | S9(9)V99 | 84 | 94 | 11 |
| 8 | EFFECTIVE_DATE | 9(8) | 95 | 102 | 8 |
| 9 | EXPIRY_DATE | 9(8) | 103 | 110 | 8 |
| 10 | STATUS | X(2) | 111 | 112 | 2 |
| 11 | FILLER | X(38) | 113 | 150 | 38 |

## `exposure_monthly.csv` (LRECL 300, RECFM=FB)

CSV text inside fixed 300-byte records: each line is right-padded with spaces to 300 bytes, then
CRLF. Record 1 is the header; one record per group follows.

```
POLICY_TYPE,BRANCH_CODE,COVERAGE_TYPE,RATING_TERRITORY,CLASS_CODE,POLICY_COUNT,TOTAL_EXPOSURE,TOTAL_PREMIUM,TOTAL_DEDUCTIBLE
```

| Column | Source | Format |
| --- | --- | --- |
| POLICY_TYPE, BRANCH_CODE | `P.` | trailing blanks trimmed; NULL → empty |
| COVERAGE_TYPE, RATING_TERRITORY, CLASS_CODE | `C.` | as above (territory/class are nullable) |
| POLICY_COUNT | `COUNT(*)` | integer. Counts **coverage rows**, not policies ([Q-33](quirk-register.md#q-33), preserved) |
| TOTAL_EXPOSURE / TOTAL_PREMIUM / TOTAL_DEDUCTIBLE | `SUM(C.COVERAGE_LIMIT / C.PREMIUM / C.DEDUCTIBLE)` | plain decimal, 2 places, leading `-` when negative; NULL sum → empty |

Fields containing `,` `"` CR or LF are quoted RFC 4180 style. Selection: active policy ⋈ active
coverage, grouped by the five dimensions, ordered by `POLICY_TYPE, BRANCH_CODE` as in the JCL, then
by the remaining group columns (the JCL leaves that order undefined; the tiebreak makes the file
deterministic). A line longer than 300 bytes fails the job rather than truncating.

Remote name: `exposure_monthly_<YYYYMMDD>.csv` (one dated file per month, Q-33 decision) instead
of overwriting `exposure_monthly.csv`. Pending: Actuarial (confirm the dated name).
