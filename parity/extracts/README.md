# Extract parity (AB-527)

Byte-for-byte checks for `dailyExtractJob` (DAILY-EXTRACT) and `monthlyExposureJob`
(MONTHLY-EXPOSURE) over **5 consecutive daily business dates** (2026-10-12 … 2026-10-16) and
**5 consecutive monthly runs** (2026-06-01 … 2026-10-01), listed in `runs.json`.

```
make parity-extracts         # oracle.py check + ExtractParityIT (needs Docker)
make parity-extracts-golden  # regenerate golden/ after an intentional, reviewed change
```

| Path | What |
| --- | --- |
| `gen_fixtures.py` | Deterministic seed data per run (`fixtures/<run>/policies.csv`, `coverages.csv`): all statuses, `LAST_UPDATED` on both sides of the window boundary, NULLs, negative and max-width amounts, EBCDIC-vs-ASCII collation traps, CSV quoting characters |
| `oracle.py` | Independent oracle (no code shared with Java): reads the SQL **verbatim from the JCL `SYSIN`**, applies three documented rewrites (DB2 date arithmetic → SQLite, EBCDIC collation + `NULLS LAST`, exposure tiebreak), runs it in SQLite and renders the files from its own layout tables |
| `golden/<run>/` | Oracle output, committed |
| `pas-batch/.../ExtractParityIT` | Loads each fixture into PostgreSQL 16 (Testcontainers + Flyway V1–V4), runs the Spring Batch job and compares every file with the golden byte for byte |

## Honest provenance

There is no mainframe in this project and no captured production file, so the goldens are **not**
mainframe output. They are what the JCL SQL selects, ordered with DB2 EBCDIC semantics, encoded
with the layouts in `docs/modernization/extract-layouts.md`. As coded, DSNTEP2 would not even
write those datasets ([Q-31](../../docs/modernization/quirk-register.md#q-31)). The ticket's
"byte-for-byte against the mainframe for 5 runs" criterion therefore stays open until a parallel
run against captured `DAILYEXT`/`MONEXP` files (Pending: Mainframe team, Claims, Actuarial). Point
`fixtures/` at an unloaded DB2 snapshot and `golden/` at the captured files to reuse this harness
for that.
