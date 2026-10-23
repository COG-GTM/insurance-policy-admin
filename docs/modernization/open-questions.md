# Open questions (AB-517)

These are questions the code cannot answer. Each has a suggested owner. The epic coordinator decides
which become follow-up Jira tickets. Until a question is answered, the related quirk keeps the decision
recorded in [quirk-register.md](quirk-register.md).

### OQ-01

What moves a policy PN→AC, AC→EX, AC→LP and to CN in production? Manual SQL, another program, or a job outside this repo? Should the new platform have an activation step?

- **Suggested owner:** Mainframe team + PAS product owner
- **Related:** Q-24, M-15

### OQ-02

Where does the `ACMEFTP` credential come from (NETRC or other)? Which FTP.DATA translation is used (EBCDIC→ASCII, record delimiters)?

- **Suggested owner:** Mainframe operations
- **Related:** Q-37, M-10

### OQ-03

Which TD queue do POLNEW/UNDWRT actually write to, given the 4-character `QUEUE` limit? How does it reach MQ, and are the request and decision messages really on separate queues?

- **Suggested owner:** Mainframe team (CICS/MQ)
- **Related:** Q-29, M-04, I-04, I-05

### OQ-04

Which DB2 DATE precompiler option or date exit is in effect? Do PIC 9(8) date host variables work in production, or do those statements fail?

- **Suggested owner:** DBA
- **Related:** Q-28, M-06

### OQ-05

Does anything outside POLEND apply endorsement changes (coverage add/remove, limit, address, cancellation)?

- **Suggested owner:** Mainframe team + PAS product owner
- **Related:** Q-13

### OQ-06

What is the effective TS queue name in the POLEND load module, is it recoverable, and who reads it?

- **Suggested owner:** Mainframe team + Compliance
- **Related:** Q-15, I-07, M-18

### OQ-07

Should `POLICIES.TOTAL_PREMIUM` (shown to CSRs, brokers and Claims) reflect the PREMBAT result? Today it never does.

- **Suggested owner:** Actuarial + PAS product owner
- **Related:** Q-36

### OQ-08

Are terrorism, catastrophe-zone and cyber scoring (listed in the UNDWRT history) still wanted? If so, with which rules?

- **Suggested owner:** Underwriting
- **Related:** Q-27

### OQ-09

What does `API_FLAG` mean (set `N` by POLNEW, `Y` in facade seed data)? Who sets it to `Y`?

- **Suggested owner:** PAS product owner + Java facade owner
- **Related:** Q-31

### OQ-10

Does the Claims import tolerate the overlapping 1-day delta, and does it need cancelled, expired or lapsed policies?

- **Suggested owner:** Claims system owner
- **Related:** Q-38, I-01

### OQ-11

Is a customer extract (`ACME.PAS.EXTRACT.CUSTOMER.DAILY`) produced anywhere, and does anyone consume it?

- **Suggested owner:** Claims system owner + Broker Portal team
- **Related:** Q-39

### OQ-12

Which housekeeping deletes the fixed-name daily extract datasets so the next run can allocate them?

- **Suggested owner:** Mainframe operations
- **Related:** Q-40, M-14

### OQ-13

Is the DSNTEP2 in `ACME.PAS.LOADLIB` site-modified? Can we get production samples of each extract and report for golden files?

- **Suggested owner:** Mainframe team
- **Related:** Q-41, M-13, M-17

### OQ-14

Which code values exist in production for policy type, status, UW status and risk tier (the facade seed has `CGL`, `A`, `B`)? Run a DISTINCT profile on DBPD.

- **Suggested owner:** DBA + data migration lead
- **Related:** Q-43, Q-23

### OQ-15

What do recent PREMRPT totals show (read / updated / errors)? Does the batch really stop at the first duplicate key, and is there a rerun or cleanup procedure?

- **Suggested owner:** Mainframe operations
- **Related:** Q-05, I-12

### OQ-16

Which DB2 objects and columns does the Broker Portal linked server query, how often, and under which auth ID?

- **Suggested owner:** Broker Portal team
- **Related:** I-03, M-08

### OQ-17

Which FileNet documents does each UW decision create? Are new-business, renewal and endorsement documents produced by some other path?

- **Suggested owner:** ECM / FileNet team
- **Related:** I-05, M-09

### OQ-18

How will the new platform read Claims history (Claims API, CDC feed or a shared read view), and with what freshness?

- **Suggested owner:** Claims team + architecture
- **Related:** I-06, Q-02

### OQ-19

Who calls the REST facade today? Which clients must keep the v1 contract during the strangler phase?

- **Suggested owner:** Java facade owner
- **Related:** I-10

### OQ-20

Who reads the premium report dataset, and what retention is required?

- **Suggested owner:** Mainframe operations + Finance
- **Related:** I-12

### OQ-21

What renewal window should apply (for example 60 days before expiry to 30 days after)? Should renewal of a UW-declined policy be blocked?

- **Suggested owner:** PAS product owner
- **Related:** Q-17

### OQ-22

Should new business accept an operator-keyed premium, or rate at bind time with pas-rating?

- **Suggested owner:** Actuarial + PAS product owner
- **Related:** Q-31

### OQ-23

What is the actual production count of active policies and coverages, and what peak book size and growth should the premium batch load test represent?

- **Suggested owner:** PAS operations + DBA
- **Related:** AB-523 premium batch load test

### OQ-24

Should migration bulk-load and delta-sync writes publish the same policy outbox events as online
transactions, or remain silent data-copy operations? AB-529 intentionally does not emit outbox events.

- **Suggested owner:** PAS product owner + integration owners
- **Related:** AB-529 migration cutover

### OQ-25

When reverse-syncing multiple underwriting decisions for one policy on the same business date, is
collapsing them to the latest `decision_id` acceptable for the DB2 `(policy_number, decision_date)`
key?

- **Suggested owner:** Underwriting + mainframe team
- **Related:** AB-529 reverse sync
AB-530: reconciliation compares the same latest-per-key projection; collapse still open

### OQ-26

COBOL coverage DML was found in `POLNEW.cbl:227` (insert) and `POLRNW.cbl:175` (update);
`POLNEW.cbl:173` writes `POLICIES.LAST_UPDATED`, and `POLRNW.cbl:162` does likewise before its
coverage update. No coverage DML was found in `POLEND.cbl` or `UNDWRT.cbl`. Delta sync therefore
uses a bounded rolling coverage-set hash sweep, persisted in `pas_batch.sync_watermark`, to detect
coverage changes that do not advance the policy watermark. Confirm there are no other production
writers or transaction paths that can change coverages independently.

- **Suggested owner:** PAS mainframe team + DBA
- **Related:** AB-529 delta sync
