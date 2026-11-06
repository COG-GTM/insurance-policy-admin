# Messaging and Broker Portal decommission (AB-528)

The plan is [`messaging-decommission.json`](messaging-decommission.json); `make messaging-decommission`
(CI: `adr-check.yml`) validates it, checks that each listed definition is still where the plan cites it,
checks the MQ sink configs in [`pas/deploy/messaging`](../../pas/deploy/messaging/README.md), and prints
a dry run. Every step is manual and outside this repository; `--execute` refuses while any gate is open
and never touches `cobol/`, `jcl/`, `java-facade/` or `sql/`.

## Definitions listed for decommission

| Id | Kind | Name | Where it is defined today | Replaced by | Owner |
|---|---|---|---|---|---|
| D1 | CICS TD queue | `ACME` (Q-13: the 29/30-character names collapse to 4 characters) | CICS CSD/DCT (outside the repo); written by `cobol/programs/POLNEW.cbl` and `UNDWRT.cbl` | `pas.underwriting.requested`, `pas.underwriting.decided` | Mainframe team |
| D2 | IBM MQ queue | `ACME.PAS.UNDERWRITING.REQUEST` | Queue manager (outside the repo); named in `POLNEW.cbl` | `pas.underwriting.requested` | MQ admins + Underwriting ops |
| D3 | IBM MQ queue | `ACME.PAS.UNDERWRITING.DECISION` | Queue manager; named in `UNDWRT.cbl` | `pas.underwriting.decided` | MQ admins + Underwriting ops |
| D4 | CICS TS queue | `ENDORSEMENT-AUDIT` | `POLEND.cbl` (no reader in the repo) | `pas.policy.endorsed` + `PASAPP.POLICY_CHANGE_AUDIT` | Mainframe team |
| D5 | IBM MQ queue | FileNet document trigger queue (name pending FU-06) | Queue manager / FileNet | `pas.policy.created`, `.renewed`, `.endorsed` | ECM + MQ admins |
| D6 | SQL Server linked server | Broker Portal linked server to DB2 (ODBC, read-only) | Broker Portal SQL Server (FU-18) | `GET /api/v1/policies` and the inquiry API | Broker Portal team |
| D7 | DB2 grants | SELECT on ACMEINS for the portal's ODBC user | DB2 catalog (FU-18) | OAuth client with `pas.policy.read` + entitlement | DBA team |
| D8 | Connect + topics | `pas-mq-sink-*`, `pas.bridge.*` (INT-01, temporary) | `pas/deploy/messaging` | native consumers of the events | Platform team |

## Gates (all pending)

G1 consumer inventory (FU-06, FU-18) · G2 FileNet generates documents for a new, a renewed and an
endorsed policy in a test environment · G3 underwriting consumers verified · G4 Broker Portal on the
API with the linked server disabled for 30 days · G5 CICS PNEW/PUWR/PRWL/PEND on `NEW` and no CICS
messages for 30 days · G6 sign-offs · G7 one release cycle with the queues disabled, not deleted.

## Steps (manual, in order)

S1 disable the MQ queues (keep definitions) · S2 remove TD queue `ACME` with the CICS retirement
(AB-530) · S3 drop the linked server and revoke grants · S4 delete the sink connectors and bridge
topics, `PAS_BRIDGE_ENABLED=false` · S5 delete the MQ definitions after G7 · S6 PR removing the bridge
package and `pas/deploy/messaging`.
