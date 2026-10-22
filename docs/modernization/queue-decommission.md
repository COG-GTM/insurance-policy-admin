# Queue decommission plan (AB-528)

This inventory covers the legacy queues and TDQ-to-MQ definitions used by the event bridge.
Queue names, consumers, and ownership must be confirmed with the responsible teams before cutover.

| Queue or definition | Current path and evidence | Owner | Replacement topic/table | Consumers to migrate | Bridge route flag | Deletion precondition |
| --- | --- | --- | --- | --- | --- | --- |
| `ACME.PAS.UNDERWRITING.REQUEST` | POLNEW `7000-SEND-MQ-MESSAGE`, CICS TDQ→MQ (I-04); definition is missing (M-04) | Underwriting operations (owner/consumer to confirm); MQ admin for MQ definition | `pas.underwriting.events.v1` (`UnderwritingReferralRequested`) | Underwriting operations/workflow consumer | `document-events` (also gates referral bridge) | All consumers migrated to Kafka; `document-events` is `retired`; MQ queue-depth and put-count metrics remain zero for 30 consecutive days; MQ admin and owner sign off |
| `ACME.PAS.UNDERWRITING.DECISION` | UNDWRT `7000-SEND-MQ-NOTIFICATION`, CICS TDQ→MQ (I-05); definition is missing (M-04) | FileNet/ECM; MQ admin for MQ definition | `pas.document.requests.v1` and the PAS outbox | FileNet document-trigger consumer | `document-events` | All consumers migrated to Kafka; `document-events` is `retired`; MQ queue-depth and put-count metrics remain zero for 30 consecutive days; MQ admin and owner sign off |
| `ENDORSEMENT-AUDIT` | POLEND CICS TS queue (I-07); reader is unknown (M-18) | Compliance (reader/owner to confirm) | `pas.policy_audit` and `PolicyEndorsed` | Unknown compliance reader; identify before migration | No TS-queue bridge; `document-events` retirement remains a global bridge precondition | All consumers migrated to Kafka; `document-events` is `retired`; TS queue-depth and put-count metrics remain zero for 30 consecutive days; MQ admin and owner sign off |
| `ACME.PAS.DOCUMENT.REQUEST` | New transitional MQ queue, bridge-only; MQ admin must define it | MQ admin (queue definition) and FileNet/ECM (consumer) | `pas.document.requests.v1` | FileNet document consumer | `document-events` | All consumers migrated to Kafka; `document-events` is `retired`; MQ queue-depth and put-count metrics remain zero for 30 consecutive days; MQ admin and owner sign off |
| TDQ→MQ bridge and DCT definitions | Missing CICS TDQ, queue-manager, queue, and trigger/initiation definitions (M-04) | Mainframe CICS systems programming and MQ admin | `pas.underwriting.events.v1` and `pas.document.requests.v1` | Underwriting workflow and FileNet/ECM consumers; confirm all MQ trigger/initiation paths | `document-events` | All consumers migrated to Kafka; `document-events` is `retired`; related queue-depth and put-count metrics remain zero for 30 consecutive days; MQ admin and owners sign off |

The 30-day observation window starts only after queue consumers have migrated. Retain the queues
and definitions until queue-depth and put-count telemetry demonstrates zero activity for 30
consecutive days and both MQ administration and the owning team approve deletion.
