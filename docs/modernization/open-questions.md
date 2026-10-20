# Open questions and proposed follow-up tickets (AB-517)

Questions the discovery could not answer from the repository. Each is written as a
**proposed** ticket (title, context, proposed owner) — none has been created in Jira; the
coordinator or PO decides which to raise.

| | |
|---|---|
| Epic / ticket | AB-516 / AB-517 |
| Status | All proposed — not yet raised in Jira |
| Reviews | Pending: Product Owner (PAS) to triage. Pending: Mainframe team lead. |

See also: [inventory](inventory.md) · [integrations](integrations.md) ·
[quirk register](quirk-register.md) · [missing artifacts](missing-artifacts.md).

| ID | Proposed ticket title | Proposed owner | Blocks / informs |
|----|-----------------------|----------------|------------------|
| [OQ-01](#oq-01) | Add POLNEW and POLQRY to the COBOL parity harness | Modernization engineering | AB-524, AB-525 |
| [OQ-02](#oq-02) | Confirm deployed load modules match repository source | Mainframe team lead | AB-525, AB-529 |
| [OQ-03](#oq-03) | Confirm whether any PREMBAT build loads TERRITORY_FACTORS; profile the table | Mainframe team + Actuarial | AB-522 |
| [OQ-04](#oq-04) | Flag-guarded table-driven territory/class factors (default off) | Actuarial + PO | after AB-522 |
| [OQ-05](#oq-05) | State-specific renewal rate caps | PO + Compliance | post-parity |
| [OQ-06](#oq-06) | Define real endorsement behaviour (type capture, pro-rata, cancel) | PO | post-parity, AB-526 |
| [OQ-07](#oq-07) | Who purges/reads PREMIUMS; production -803 history | Batch ops + DBA | AB-523 |
| [OQ-08](#oq-08) | Capture byte-level samples and specs of Claims/Actuarial files | Claims team, Actuarial, Mainframe ops | AB-527 |
| [OQ-09](#oq-09) | Profile DB2 data for invalid/sentinel dates and colliding numbers | DBA team | AB-529 |
| [OQ-10](#oq-10) | Confirm Enterprise COBOL behaviour for divide-by-zero and INTEGER-OF-DATE on invalid dates | Mainframe team lead | AB-526 |
| [OQ-11](#oq-11) | Configurable underwriting thresholds; loss-ratio overflow fix | Underwriting owner | post-parity, AB-525 |
| [OQ-12](#oq-12) | Identify consumers of the underwriting TD queues and the FileNet trigger | Mainframe team + ECM team | AB-528 |
| [OQ-13](#oq-13) | Identify the reader of the ENDORSEMENT-AUDIT TS queue | Compliance + Mainframe team | AB-526, AB-528 |
| [OQ-14](#oq-14) | Inventory Broker Portal linked-server queries and agree a replacement | Broker Portal team | AB-528 |
| [OQ-15](#oq-15) | Identify REST facade consumers | T. Nguyen (facade owner) | AB-524, AB-530 |
| [OQ-16](#oq-16) | Export CA-7 schedules and dependencies for PAS0050/0100/0200 | Batch ops | AB-523, AB-527, AB-530 |
| [OQ-17](#oq-17) | Explain DB2 DATE ↔ PIC 9(8) host-variable conversion | DBA team | AB-521, AB-529 |
| [OQ-18](#oq-18) | Identify who maintains POLICY_HOLDERS and whether customer PII is extracted | Security + PO | AB-521, AB-527 |
| [OQ-19](#oq-19) | Renewal of long-expired policies and the unused COVID grace flag | PO | AB-526 |
| [OQ-20](#oq-20) | Find the "mass renewal" batch POLRNW mentions | Mainframe team | AB-526 |

## Details

### OQ-01

**Add POLNEW and POLQRY to the COBOL parity harness.** Context: only PREMBAT, POLRNW,
POLEND and UNDWRT have goldens (`parity/programs/`). POLNEW holds two significant new
findings ([Q-27](quirk-register.md#q-27), [Q-28](quirk-register.md#q-28)) and POLQRY
([Q-30](quirk-register.md#q-30)) has none pinned. Write spec/fixtures/stubs per
`parity/README.md` before AB-525/AB-524 port them. Proposed owner: modernization engineering.

### OQ-02

**Confirm deployed load modules match repository source.** Context: POLNEW as written cannot
insert a valid policy (dates unset, number collisions — Q-27, Q-28), so either production runs
different code (COBOL/JCL live in RTC, README L125) or there is an unexplained conversion
layer. Compare RTC baselines and load-module compile dates with this repo; capture real
policy numbers to confirm the `POL` + 9-digit pattern. Proposed owner: Mainframe team lead.

### OQ-03

**Does any PREMBAT build load `TERRITORY_FACTORS`?** Context: PREMBAT opens and closes the
cursor without fetching ([Q-03](quirk-register.md#q-03)) and comments "production loads all
rows". Confirm from the production module and premium history; profile table contents and
who maintains it ([I-13](integrations.md#i-13)). Proposed owner: Mainframe team + Actuarial.

### OQ-04

**Flag-guarded table-driven territory/class factors.** Context: the epic's only intended
rating change. Must stay behind a feature flag that is **off by default**, with its own
parity baseline and actuarial sign-off. Proposed owner: Actuarial + PO.

Proposed ticket: *[PAS] Turn on filed territory/class factors (FILED factor set)*. AB-522 built
the switch; this ticket is the business decision to use it.

- Engineering already in place (AB-522): `pas-rating` reads `pas.territory_factors` /
  `pas.class_factors` for the factor set in `pas.rating.factor-set` (`PAS_RATING_FACTOR_SET`, both
  `pas-policy-service` and `pas-batch`). Default `PARITY` = catch-all `1.0000` rows, reproducing
  PREMBAT ([Q-03](quirk-register.md#q-03)). `FILED` is selectable but ships with no rows, and
  rating refuses to load a factor set without territory and class factors, so it cannot be
  switched on by accident.
- To do: load the rating department's filed territory/class tables into `FILED` with a new
  Flyway migration or a controlled data load (effective-dated rows, a `*` row per table if
  unmatched codes should get a default factor), and source the territory and class code for each
  policy (PREMBAT reads neither; `RatingRequest` already carries both).
- Needs its own parity baseline (expected premiums under the filed factors), an entry in
  `parity/known-differences.json` pointing at Q-03, and a dual-run comparison before the flag
  changes in any environment.
- Sign-offs: Pending: Actuarial (factor values), Pending: Product owner (go-live decision).

### OQ-05

**State-specific renewal rate caps.** Context: [Q-04](quirk-register.md#q-04); README
"Known Limitations" #4. Regulatory requirement to be specified by compliance; outside the
parity port. Proposed owner: PO + Compliance.

### OQ-06

**Define real endorsement behaviour.** Context: POLEND never captures the endorsement type,
never computes the adjustment, never applies cancellations or coverage/limit/address changes
([Q-10](quirk-register.md#q-10), [Q-11](quirk-register.md#q-11),
[Q-15](quirk-register.md#q-15)). How do endorsements really happen in production today
(manual DBA fixes? another system?) and what should the API do after parity? Proposed owner: PO.

### OQ-07

**PREMIUMS lifecycle.** Context: the PK prevents any second calculation per term
([Q-22](quirk-register.md#q-22)). Confirm from job logs how often PREMIUM-BATCH ends RC 4,
whether something purges or archives PREMIUMS, and who reads it (billing? the Broker Portal?).
Proposed owner: Batch ops + DBA.

### OQ-08

**Byte-level samples and specs of the outbound files.** Context: the JCL uses `DSNTEP2`,
which does not write the `POLOUT`/`COVOUT`/`EXPOUT` DDs, so the real layouts are unknown
([Q-31](quirk-register.md#q-31), [Q-33](quirk-register.md#q-33)). Capture production files
([M-09](missing-artifacts.md#m-09)) and the consumers' parser specs; these become AB-527's
byte-level goldens. Proposed owner: Claims team, Actuarial, Mainframe ops.

### OQ-09

**Data profiling for migration.** Context: count policies/coverages with invalid dates
(e.g. Feb 29 in non-leap years from [Q-01](quirk-register.md#q-01)), `99991231` sentinels
([Q-07](quirk-register.md#q-07)), zero premiums, risk scores ≥ 1000, `RS`/`RM` UW statuses,
and gaps in policy numbering ([Q-27](quirk-register.md#q-27)). Proposed owner: DBA team.

### OQ-10

**Enterprise COBOL runtime behaviour.** Context: goldens were produced by GnuCOBOL 3.1.2.
Confirm on z/OS what `COMPUTE` does on divide-by-zero without `ON SIZE ERROR`
([Q-08](quirk-register.md#q-08)) and what `FUNCTION INTEGER-OF-DATE` returns for an invalid
date ([Q-13](quirk-register.md#q-13)); if they differ, add known-differences entries or
adjust goldens. Proposed owner: Mainframe team lead.

### OQ-11

**Configurable underwriting rules.** Context: score bands, claim-count and limit surcharges,
and the $500M branch accumulation limit are hardcoded ([Q-21](quirk-register.md#q-21));
the loss-ratio overflow ([Q-18](quirk-register.md#q-18)) and unsigned wrap
([Q-16](quirk-register.md#q-16)) favour risky applicants. Candidate post-parity fixes.
Proposed owner: Underwriting business owner.

### OQ-12

**Consumers of the underwriting queues and the FileNet trigger.** Context: POLNEW and UNDWRT
write TD queues named `ACME.PAS.UNDERWRITING.REQUEST` / `.DECISION`; README says MQ events
drive FileNet. Identify the TDQ→MQ mapping, every consumer, which messages create FileNet
documents, and who (if anyone) starts `PUWR` from a request ([I-04](integrations.md#i-04),
[I-05](integrations.md#i-05), [I-06](integrations.md#i-06)). Proposed owner: Mainframe team +
ECM team.

### OQ-13

**Reader of `ENDORSEMENT-AUDIT`.** Context: written by POLEND "for regulatory compliance"
([I-07](integrations.md#i-07)); the name is longer than CICS allows and TS queues are not
durable by default. Who reads it, and is it actually the regulatory audit trail? Proposed
owner: Compliance + Mainframe team.

### OQ-14

**Broker Portal linked server.** Context: [I-03](integrations.md#i-03); queries and tables
unknown ([M-08](missing-artifacts.md#m-08)). Inventory them and agree the replacement
(authenticated API or read replica) before DB2 retires. Proposed owner: Broker Portal team.

### OQ-15

**REST facade consumers.** Context: the facade has no authentication, so no access log ties
calls to clients ([I-09](integrations.md#i-09)). Identify consumers from network/proxy logs
so each can migrate to the authenticated inquiry API. Proposed owner: T. Nguyen.

### OQ-16

**CA-7 schedules.** Context: [M-05](missing-artifacts.md#m-05). Export job definitions,
predecessors, calendars and restart instructions so the Java scheduler preserves ordering
(e.g. extract after the day's online activity) during parallel run. Proposed owner: Batch ops.

### OQ-17

**DATE host-variable conversion.** Context: DB2 `DATE` columns are fetched into PIC 9(8)
(POLRNW, POLEND, POLQRY, PREMBAT) and POLNEW inserts X(10) host variables; standard DB2
requires character host variables for dates. Confirm precompiler/bind options or exits
([M-07](missing-artifacts.md#m-07)). Proposed owner: DBA team.

### OQ-18

**Customer master and PII in extracts.** Context: nothing in PAS writes `POLICY_HOLDERS`
([I-12](integrations.md#i-12)); DAILY-EXTRACT declares a customer extract that is never
produced. Confirm the master's owner and whether customer PII must (or must not) flow to
Claims. Proposed owner: Security + PO.

### OQ-19

**Renewal edge cases.** Context: POLRNW renews any `EX` policy with effective date = old
expiry, however long ago (backdated term), and the 2020 `WS-COVID-GRACE` flag is never used
(`POLRNW.cbl` L34-37, L106-109). Confirm intended rules for late renewals and whether any
grace logic exists elsewhere. Proposed owner: PO.

### OQ-20

**Mass-renewal batch.** Context: `POLRNW.cbl` L15 says "Batch mode supported via PREMBAT
for mass renewals", but PREMBAT only writes PREMIUMS. Is there a renewal batch outside the
repo ([M-10](missing-artifacts.md#m-10)) that AB-526 must also replace? Proposed owner:
Mainframe team.

## AB-521 (target data model)

### Proposed: Implement unique policy numbering for Q-27
- **Context:** quirk register [Q-27](quirk-register.md#q-27) (Fix, AB-525). AB-521 keeps
  `pas.policy_seq` identical to DB2 and `PolicyNumbers.fromSequence` reproduces POLNEW at parity
  (GnuCOBOL check: sequence 1000000..1000009 all give `POL000100000`), so
  `PolicyNumberGenerator.nextPolicyNumber()` must not be used for inserts until AB-525 implements
  the `POL` + 9-digit unique scheme. `java-facade` sample data uses yet another format
  (`POL-00000001`).
- **Proposed owner:** PO + DBA (decision), AB-525 implementer.

### Proposed: Load filed territory and class factors
- **Context:** `pas.territory_factors` / `pas.class_factors` have a `FILED` factor set that ships
  empty: neither the COBOL nor DB2 DDL contains real factor values. Rating stays on `PARITY` (all
  1.0000) by default via `pas.rating.factor-set`. Someone has to provide the filed tables and the
  effective dates, and sign off turning them on.
- **Proposed owner:** Rating/actuarial (data), AB-522 follow-up (flag).

### Proposed: Agree the Claims read interface
- **Context:** see `claims-interface-assumptions.md`. Needed before AB-525/AB-526 port UNDWRT and
  POLRNW claims checks.
- **Proposed owner:** Claims team + PAS tech lead.

### Proposed: Security review of PII handling
- **Context:** see `pii-handling.md`. Open items: KMS/secret store and rotation, whether
  name/address/phone/email also need field encryption, masking rules for extracts.
- **Proposed owner:** Security.

## AB-524 (policy inquiry API)

### Proposed: Issue PAS entitlement claims from the IdP
- **Context:** `pas-policy-service` authorises reads with the `agent_code`, `branch_code` and
  `book` claims (ADR 0001 D5, `CallerEntitlements`). A token with none of them sees no policy.
  The IdP must issue them per user (Broker Portal, CSRs) and per client (system integrations,
  usually `book` = `*`), and `policy:pii:read` only to roles allowed to see unmasked
  policyholder data. Also confirm the `book` value format (`*`, `agent:<code>`,
  `branch:<code>`).
- **Proposed owner:** IdP team + Security.

### Proposed: Prometheus scrape credentials
- **Context:** only the health probes are anonymous; `/actuator/prometheus` needs a valid JWT.
  The platform scraper needs a client-credentials token, or Security accepts a separate
  management port reachable only from the monitoring network.
- **Proposed owner:** Platform/SRE + Security.

### Proposed: Complete the facade consumer inventory and route flags
- **Context:** `facade-retirement.md` sections 2-3; extends [OQ-15](#oq-15). Gateway route
  flags per consumer and 30-day traffic-to-zero measurement are prerequisites for AB-530.
- **Proposed owner:** T. Nguyen + gateway team.

### Proposed: Add POLQRY to the parity harness if CICS inquiry stays in dual-run
- **Context:** [OQ-01](#oq-01). AB-524 replaces POLQRY with an API whose contract is the
  facade snapshot, not the BMS screen, so there is no COBOL output to diff. If the PQRY 3270
  screen must run in parallel with the API during cutover, characterise POLQRY's map output
  first.
- **Proposed owner:** PAS tech lead.


## AB-527 (Claims and Actuarial extracts)

### Proposed: Capture production DAILYEXT / MONEXP files for the byte-level parallel run
- **Context:** `parity/extracts/` goldens come from an independent oracle, not the mainframe
  ([Q-31](quirk-register.md#q-31): DSNTEP2 writes to SYSPRINT as coded, so what Claims/Actuarial
  actually receive is unknown, [OQ-08](#oq-08)). Capture 5 consecutive daily files and one monthly
  file plus the matching DB2 unloads, then run the same harness against them.
- **Proposed owner:** Mainframe team + PAS tech lead.

### Proposed: Claims and Actuarial ingest confirmation and cut-over dates
- **Context:** consumers must confirm the layouts in `extract-layouts.md`, the `.sha256` /
  `.done` convention, SFTP with key auth, and Actuarial's dated file name
  `exposure_monthly_<YYYYMMDD>.csv` ([Q-33](quirk-register.md#q-33)). Cut-over dates are
  "Pending: Claims/Actuarial" in `extracts-runbook.md`.
- **Proposed owner:** Claims team, Actuarial (named contact TBC).

### Proposed: Provision SFTP accounts, keys and host-key pins
- **Context:** `pas-batch` needs per-consumer SFTP users, an SSH key pair in the secrets store
  (`PAS_*_SFTP_PRIVATE_KEY_FILE`), and a `known_hosts` with the consumer servers' keys. Decide
  whether an enterprise MFT hub replaces direct SFTP (ADR 0001 D6 alternative).
- **Proposed owner:** Security + Platform.

### Proposed: Remove the FTP steps and CA-7 entries after cut-over
- **Context:** `jcl/DAILY-EXTRACT.jcl` STEP030 and `jcl/MONTHLY-EXPOSURE.jcl` still FTP in clear
  text; AB-527 does not edit `jcl/` (compat gate). Retire them, and CA-7 `PAS0100`/`PAS0200`, once
  each consumer is live on SFTP.
- **Proposed owner:** Mainframe team (AB-529 decommission).

### Proposed: Scheduler leadership for multiple pas-batch replicas
- **Context:** `ExtractScheduler` assumes one scheduling replica. If `pas-batch` scales out, add a
  lock (e.g. ShedLock on `pas_batch`) or move triggering to the platform scheduler.
- **Proposed owner:** Platform.


## AB-523 (premium batch)

### Proposed: Confirm the full-book volume and batch window
- **Context:** no production volume data exists in the repo. AB-523 sized the load test at an
  assumed 1,000,000 active policies (see `docs/modernization/runbooks/premium-batch.md`, "Load
  test"). Real active-policy count, growth and the batch window are **Pending: Ops**; rerun the
  load test generator at that size before go-live.
- **Proposed owner:** Batch operations + DBA.

### Proposed: Cut-over of CA-7 PAS0050 to the pas-batch scheduler
- **Context:** `PAS_BATCH_PREMIUM_CRON` stays `-` (disabled) until the PREMIUMS cut-over (AB-529
  / AB-530): before the flip only CDC writes to `pas`. At cut-over: remove PAS0050 from CA-7,
  set the cron (`0 0 1 * * *`, zone `America/New_York`), and point downstream CA-7 successors (if
  any — not visible in the repo, see [OQ-07](#oq-07)) at the new completion signal
  (`pas_batch.premium_run_summary` / `pas_batch_premium_runs_total`).
- **Proposed owner:** Batch operations + Mainframe scheduling.

### Proposed: Single-launcher guarantee for multiple pas-batch replicas
- **Context:** `runDate` is the identifying job parameter, so two replicas firing the same night
  cannot both complete the same date (Spring Batch rejects the second launch while the first is
  running or complete). A strict leader lock (e.g. ShedLock) is not added; run pas-batch with one
  replica or add a lock if the platform requires several.
- **Proposed owner:** Platform team.

### Proposed: Business sign-off of the Q-22 / Q-35 fixes and report change (Q-24)
- **Context:** reruns replace PREMIUMS rows (Q-22), a failed write no longer stops the run
  (Q-35), and PREMRPT prints edited amounts (Q-24). Recorded as proposed decisions.
- **Proposed owner:** PO (Pending: PO), Batch operations (Pending: Ops).

## AB-529 (data migration, reconciliation, parallel run)

### Proposed: Choose and provision DB2 log-based CDC
- **Context:** AB-529 ships a timestamp/watermark delta sync as the CDC fallback
  ([data-migration.md](data-migration.md#ongoing-sync-timestamp-deltas-now-cdc-recommended)); it cannot
  see deletes or updates that do not move `LAST_UPDATED`. Pick IBM IIDR or Debezium Db2, provision
  capture on ACMEINS and a read-only JDBC user for reconciliation, and feed the existing `TargetWriter`.
- **Proposed owner:** Mainframe team + Platform.

### Proposed: Run the full-billing-cycle parallel run
- **Context:** the harness (`parallelRunDiffJob`, [parallel-run.md](parallel-run.md)) is ready, but
  needs daily mainframe captures in `pas.migration.legacy-output-dir`, daily shadow-mode
  `premiumRecalculationJob` runs, agreed cycle dates and a named triage owner.
- **Proposed owner:** Ops + PAS tech lead; acceptance by Product Owner, Claims, Actuarial.

### Proposed: Schedule the premium job in shadow mode for the parallel run
- **Context:** `premiumRecalculationJob` supports `PAS_BATCH_PREMIUM_WRITE_TARGET=shadow` (writes
  `pas_shadow.premiums` only). For the billing-cycle run, enable `PAS_BATCH_PREMIUM_CRON` in shadow
  mode next to CA-7 PAS0050, before the parallel-run diff. Switch to `pas` only at the PREMIUMS cut-over
  (see "Cut-over of CA-7 PAS0050" above).
- **Proposed owner:** Batch operations.

### Proposed: Production rollback rehearsal and window
- **Context:** only a local Compose rehearsal exists ([rollback-rehearsal-local.md](rollback-rehearsal-local.md)).
  Rehearse against the real gateway route flags and DB2, agree the cut-back window (proposed < 60 min)
  and build PostgreSQL → DB2 reverse replication for after a capability goes `new`.
- **Proposed owner:** Ops + Platform + Mainframe team.

### Proposed: Publish daily reports to the team channel
- **Context:** reports land in `pas.migration.report-dir`, `pas.migration_check_runs`,
  `/actuator/migration` and Prometheus. Post `latest.md` to the team channel and add alerts on
  `pas_migration_unexplained > 0` and a stale `pas_migration_last_run_seconds`.
- **Proposed owner:** Platform (observability).

### Proposed: Move POLICY_SEQ at cutover
- **Context:** `pas.policy_seq` must start above DB2 `ACMEINS.POLICY_SEQ` before POLNEW traffic moves
  (see also Q-27). Not part of the bulk load.
- **Proposed owner:** AB-525 / AB-530.
