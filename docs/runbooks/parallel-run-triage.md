# PAS parallel-run triage

Create a cycle for the agreed business-date range, then run the premium and extract comparisons for
each calendar date in that range:

```sh
cd pas-platform
./dev/parallel-run-triage.sh create pre-cutover 2026-10-01 2026-10-03
./dev/parallel-run-triage.sh list 2026-10-01
```

Use `parallelRunDiffJob` with the business date and returned cycle ID:

```sh
./mvnw -pl pas-batch spring-boot:run \
  -Dspring-boot.run.arguments="--pas.batch.cli.job=parallelRunDiffJob --pas.batch.cli.business-date=2026-10-01 --pas.batch.cli.cycle-id=<cycle-id>"
```

The API routes require a JWT with `batch:operate`. Mint an operator token through
`dev/dev-jwt.sh`; the batch API records the token's `sub` as the operator. Review differences using
`GET /api/batch/migration/parallel-run/differences?businessDate=YYYY-MM-DD&classification=UNTRIAGED`.
Use `./dev/parallel-run-triage.sh classify <id> <classification> [quirk-id] [note]` to classify a
present difference. `APPROVED_FIX` requires a quirk-register ID whose Decision contains `Fix`;
`BUG` and `DATA_ISSUE` require a nonblank note. Every update is appended to
`pas_batch.parallel_run_triage_log`.

Re-running a date updates the existing comparison rows, preserves classification only while the
compared values remain unchanged, and marks disappeared differences `present=false`. A cycle is
`CLEAN` only after every date in its inclusive range has run and there are no present `UNTRIAGED` or
`BUG` differences. `APPROVED_FIX` and `DATA_ISSUE` do not block a clean cycle.
