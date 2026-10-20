# Parallel run and difference triage (AB-529)

Goal (epic success criterion): at least **one full billing cycle** where the new premium batch and
extracts run next to the mainframe every day and the daily diff has **zero unexplained
differences**. Unexplained = `BUG` + `UNCLASSIFIED`.

Status: harness built and tested locally/CI. The real billing-cycle run against mainframe output is
**Pending: Mainframe/Platform/Ops** — it has not started, and nothing in this repo is evidence of it.

## What is compared each day

`parallelRunDiffJob` (06:00, previous business date; or `--pas.migration.cli=parallel-run`) runs every
`OutputAdapter` scheduled for the date:

| Output (scope) | Legacy side | New side | Key |
|---|---|---|---|
| `dailyExtractJob/policy_extract.dat` | `<legacy-output-dir>/dailyExtractJob/<date>/policy_extract.dat` | AB-527 `dailyExtractJob` output | `POLICY_NUMBER` |
| `dailyExtractJob/coverage_extract.dat` | same folder, `coverage_extract.dat` | AB-527 output | `POLICY_NUMBER\|SEQUENCE_NUM` |
| `monthlyExposureJob/exposure_monthly.csv` (1st of month) | same folder layout | AB-527 `monthlyExposureJob` output | first 5 CSV columns |
| `PREMIUMS` | DB2 `ACMEINS.PREMIUMS` with `CALC_DATE` = date (PREMBAT output) | `pas_shadow.premiums`, latest `shadow_run_id` for the date | DB2 primary key |

Fixed-width records are diffed field by field with the AB-527 `RecordLayout`s; premiums with the
reconciliation canonical form. Kinds: `CHANGED` (with field names), `MISSING_IN_NEW`, `EXTRA_IN_NEW`.
If either side of an output is missing, the output is `LEGACY_NOT_AVAILABLE` / `NEW_NOT_AVAILABLE`
and the day is `INCOMPLETE` — never PASS.

**Legacy captures.** Operations drop the mainframe datasets (DAILYEXT policy/coverage, MONEXP) for each
business date into `pas.migration.legacy-output-dir` in the folder layout above. Until real captures
exist (open question "Capture production DAILYEXT / MONEXP files", [Q-31](quirk-register.md#q-31)),
local and CI runs use the AB-527 oracle goldens in `parity/extracts/golden/` as the stand-in; reports
say so in their provenance.

**AB-523 premium output.** During the parallel run, `premiumRecalculationJob` runs with
`pas.batch.premium.write-target=shadow` (`PAS_BATCH_PREMIUM_WRITE_TARGET`). In that mode it writes the
same PREMIUMS rows to `pas_shadow.premiums` (Flyway V6), not to `pas.premiums`, which only the DB2
sync writes while DB2 is authoritative. Each row carries `shadow_run_id =
premiumRecalculationJob-<job instance id, 12 digits>` and `business_date` = the job's `businessDate`.
A restart reuses the shadow run; `rerun=true` creates a new one. `PremiumJobOutputSource` compares
the latest `COMPLETED` run for the date in `pas_batch.premium_run_summary` whose `write_target` is
`SHADOW` (`pas_batch` V3). `COMPLETED_WITH_ERRORS` runs count too, and their skipped rows show up as
`MISSING_IN_NEW`. A shadow run that wrote no rows (empty active book) compares as empty output. If
there is no shadow run for the date (none at all, or only `pas` runs), `PREMIUMS` reports
`NEW_NOT_AVAILABLE` and the day is `INCOMPLETE`. Schedule:
enable `PAS_BATCH_PREMIUM_CRON` in shadow mode next to CA-7 PAS0050, or launch it through
`POST /api/batch/premium/runs?runDate=`, before `parallel-run-cron`. Another output plugs in by adding
an `OutputAdapter` bean (or a `PremiumOutputSource`).

## Triage

Every difference is classified by `parity/parallel-run/triage-register.json` (also used by the
reconciliation). Anything not matched is `UNCLASSIFIED`.

| Classification | Meaning | Required in the register entry | Counts as unexplained |
|---|---|---|---|
| `BUG` | New platform is wrong | `ticket` | yes, until fixed and the entry removed |
| `APPROVED_FIX` | Intentional change vs COBOL | `quirk` (`Q-nn`) and `decision` = `docs/modernization/quirk-register.md#q-nn` (anchor must exist) | no |
| `DATA_ISSUE` | DB2 data / timing problem, not code | `owner` | no |
| `UNCLASSIFIED` | Not triaged | — (cannot be written in the register) | yes |

Entry fields follow `parity/known-differences.json`: `{id, check, scope, key (glob), kind, field,
legacy, candidate, classification, quirk, decision, ticket, owner, rationale, status}`. An entry
applies only if every given field matches; `legacy`/`candidate` pin exact values. Invalid entries
fail the job at start-up. Quirk decisions remain **Proposed — pending PO sign-off**; an
`APPROVED_FIX` entry records the link, it does not record sign-off.

Current entries: none. Proposed for triage when the first shadow runs are compared: `PREMIUMS` `EXTRA_IN_NEW` →
`APPROVED_FIX` [Q-22](quirk-register.md#q-22) (new job replaces a term's row on rerun; PREMBAT keeps
the first), added per key (or `legacy`/`candidate`-pinned) after checking DB2 holds an earlier row for
the same term, never as a `*` wildcard.

### Daily process

1. 06:00 job writes `<report-dir>/parallel-run/<date>/<run>.json|.md` and `latest.*`, a row in
   `pas.migration_check_runs`, and the gauges; `/actuator/migration` shows status and consecutive
   passing days.
2. Triage owner (PAS tech lead, rotating) reviews every `UNCLASSIFIED` difference the same day:
   - new-platform defect → raise a ticket, add a `BUG` entry with the ticket;
   - intentional behaviour change → only if the quirk register has a decision for it, add an
     `APPROVED_FIX` entry linking `#q-nn`; otherwise raise it with the PO first;
   - DB2 data or timing (late change after the extract, bad legacy data) → `DATA_ISSUE` with an owner.
3. Register changes go through a reviewed PR. Re-run the day (`parallel-run` CLI with
   `--pas.migration.business-date`) after a fix to show the difference is gone; then delete the BUG entry.
4. A billing cycle counts only if every business date in it is `PASS` (not `INCOMPLETE`) on its final
   run. `consecutivePassingDays` in `/actuator/migration` tracks this.

## Sign-offs

- Billing-cycle dates and start: Pending: Ops + Product Owner.
- Mainframe captures delivered daily: Pending: Mainframe team.
- Cycle result accepted (zero unexplained): Pending: Product Owner, Claims, Actuarial.
