# PAS architecture after cutover and decommission (AB-530)

The end state of epic AB-516, once [cutover](cutover-runbook.md) and the
[mainframe decommission](mainframe-decommission.md) are complete. Decisions and rationale are in
[ADR 0001](../adr/0001-pas-target-architecture.md); this page records what is left running.

```
channels / Broker Portal / ops ──> API gateway (IdP JWT) ──> pas-policy-service ──> PostgreSQL ACMEINS
                                                                 │ outbox                ▲
                                                                 ▼                       │
                                         Kafka (pas.policy.*, pas.underwriting.*)   pas-batch jobs
                                                │                                   premiumRecalculation
                                                ▼                                   claimsDailyExtract ──SFTP──> Claims
                          MQ bridge (until AB-528 G5) ──> FileNet, UW work queue   actuarialMonthlyExposure ──SFTP──> Actuarial
```

| Capability | Before | After |
|---|---|---|
| C-1, C-2 Rating and premium batch | PREMBAT, CA-7 PAS0050 | `pas-rating` in `premiumRecalculation` on the platform scheduler |
| C-3 Policy inquiry | PQRY, `java-facade` | `GET /api/v1/policies/**` |
| C-4..C-7 New business, underwriting, renewal, endorsement | CICS PNEW, PUWR, PRWL, PEND | policy, underwriting and change APIs |
| C-8, C-9 Claims and Actuarial feeds | CA-7 PAS0100, PAS0200, plain FTP | `pas-batch` jobs, key-only SFTP |
| C-10 Events and Broker Portal | TD queue `ACME`, ODBC linked server | Kafka domain events via the outbox, `GET /api/v1/policies` |
| C-11 System of record | DB2 ACMEINS | PostgreSQL ACMEINS |

Removed: CICS region definitions for PAS, CA-7 PAS jobs, `ACME.PAS.LOADLIB`, DB2 ACMEINS (archived),
`java-facade`, CDC/reverse sync and `acmeins_shadow`, and finally the MQ bridge once its consumers
read Kafka. In the repository, `cobol/`, `jcl/` and `java-facade/` leave `main` in the decommission PR;
`sql/` stays as the archived schema baseline.
