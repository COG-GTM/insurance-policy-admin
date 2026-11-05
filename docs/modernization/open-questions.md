# Open questions and proposed follow-up tickets (AB-517)

The ticket's acceptance criteria ask for open questions to be raised as follow-up tickets. Under the epic's working rules this run does **not** create Jira tickets, so each item below is written ready to file: proposed title, question, owner and the tickets it blocks. Once filed, replace the `FU-nn` id with the Jira key here and in [`quirks.json`](quirks.json).

| ID | Proposed ticket title | Question(s) to answer | Owner | Related quirks | Blocks |
|---|---|---|---|---|---|
| FU-01 | Decide the 29-Feb renewal convention | When a term ends on 29 Feb, should the renewed term end on 28 Feb or 1 Mar of the next year? Is there a regulatory rule per state? | Product Owner | Q-01 | AB-526 |
| FU-02 | Renewal rules not enforced today | Should renewal block at >3 open claims (as intended in 2005)? Should renewal trigger underwriting (UW_STATUS reset to PN)? What is the maximum lapse for renewing an EX policy? Is the COVID grace flag dead? | Product Owner + Underwriting | Q-02, Q-39 | AB-526 (post-parity) |
| FU-03 | Table-driven rating factors | Provide the authoritative TERRITORY_FACTORS contents and the source tables for class factors and experience mods. Approve the premium impact report of switching from 1.00 to the table values. | Actuarial / Pricing | Q-03 | AB-522, AB-523 |
| FU-04 | State-specific rate caps and premium tax | Supply per-state renewal caps and tax rates (today a flat 15% and 3.5%). Confirm these are post-migration changes. | Compliance + Actuarial | Q-04, Q-27 | post-epic |
| FU-05 | Confirm the repo source matches production load modules | Provide compile listings or a load-module comparison for POLNEW, POLEND, PREMBAT. How are policy numbers really generated (the STRING overflow)? Where are new-policy dates set? Which program activates PN → AC? Capture real PNEW/PEND/PUWR inputs and outputs from a test region. | Mainframe team | Q-08, Q-09, Q-19, Q-29, Q-40 | AB-520, AB-525, AB-526 |
| FU-06 | Document TDQ → MQ → FileNet / UW queue routing | Provide CSD/DCT for TDQ `ACME`, MQ object definitions and trigger/bridge config. Which consumers read the request and decision messages, and do they parse the 125-byte POLICY-RECORD? Which messages trigger FileNet documents? | Mainframe team + MQ admins + ECM team | Q-13 | AB-528 |
| FU-07 | Provide BMS mapsets | Source for POLNMAPS, POLRMAPS, POLEMAPS, POLQMAPS, UWRTMAPS (fields, attributes, messages). | Mainframe team | Q-19, Q-24 | AB-524..AB-526 |
| FU-08 | Endorsement specification | Pro-rata rule (pro-rata of which amount?). Behaviour per type CAD/CRM/LCH/ACH. What CAN must do (status CN? refund?). Back-dated endorsements. | Product Owner + Actuarial | Q-20, Q-21 | AB-526 (post-parity) |
| FU-09 | Endorsement audit requirements | What must the regulatory audit contain, and for how long? Who reads TSQ ENDORSEMENT-AUDIT today? | Compliance | Q-23 | AB-526 |
| FU-10 | Facade consumers and API_FLAG semantics | List every consumer of the facade. Should API_FLAG='N' policies be hidden from API consumers? | t.nguyen@acme.com + Product Owner | Q-24, Q-36 | AB-524 |
| FU-11 | Premium batch rerun and restart behaviour | How does production avoid the PREMIUMS duplicate key on daily reruns (pre-delete job? different key?). What is the commit/restart practice? Agree the idempotency key for the Java job. | Mainframe team + Actuarial | Q-25, Q-26, Q-38 | AB-523 |
| FU-12 | CA-7 schedule, cleanup and alerting | Export the CA-7 definitions for PAS0050/0100/0200 (triggers, predecessors, restart), the dataset cleanup jobs, and who is paged on failures. | Mainframe operations | Q-28, Q-33 | AB-523, AB-527, AB-530 |
| FU-13 | Capture production output samples | Provide masked samples of the premium report, policy/coverage extracts and exposure file, plus the actual unload utility used (DSNTEP2 vs DSNTIAUL vs custom). | Mainframe team | Q-29, Q-30 | AB-520, AB-523, AB-527 |
| FU-14 | Downstream feed contracts | Claims: confirm the delta-policy / full-coverage semantics, the missing customer extract and the byte layout. Actuarial: confirm POLICY_COUNT semantics and row order. Name the sign-off owners. | Claims team + Actuarial | Q-31, Q-34 | AB-527 |
| FU-15 | Replace plain FTP | Agree SFTP/managed-transfer endpoints, keys and cutover with Claims and Actuarial. | Security + Claims + Actuarial | Q-32 | AB-527 |
| FU-16 | Production data profiling | Profile POLICY_TYPE, COVERAGE_TYPE, RISK_TIER, UW_STATUS, POLICY_STATUS, policy-number formats and orphan POLICYHOLDER_IDs in DBPD. | DBA team | Q-35, Q-37 | AB-521, AB-529 |
| FU-17 | Agreed read interface for ACMEINS.CLAIMS | Define the interface (API/view) giving open-claim counts and 5-year incurred totals per policy, its SLA and its owner. | Claims team | Q-02, Q-15 | AB-525, AB-526 |
| FU-18 | Broker Portal DB2 access inventory | Export the SQL Server linked-server definition and the queries used: which tables and columns, grants, refresh frequency. | Broker Portal team + DBA | none | AB-528 |
| FU-19 | Name the owners | Replace the role placeholders (Product Owner, Claims team, Actuarial lead, Underwriting lead, Compliance, Broker Portal, ECM) with named people for the quirk register and the integration table. | Programme manager | all | AB-517 sign-off |
| FU-20 | Zero-premium renewal behaviour | What does production do when PRWL renews a policy with TOTAL_PREMIUM 0 (abend ASRA / S0CB, or something else)? Should the Java renewal renew at 0% change or reject the renewal? Can zero-premium AC/EX policies exist in DBPD? | Product Owner + Mainframe team | Q-41 | AB-526 |

## Questions about the scope of this inventory

- The README calls DAILY-EXTRACT a feed for "Claims/Broker", but the Broker Portal reads DB2 directly (I-3). Is there a second Broker feed outside this repo?
- The README says COBOL/JCL live in RTC and the facade in SVN. Is this repository an authoritative copy, or a snapshot that may lag production (affects every *needs verification* item)?
