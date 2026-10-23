# Architecture Decision Records

Architecture decisions for the PAS modernization (epic AB-516) and later work. ADRs that hit an
ARB trigger (`ARB_REQUIRED`) need Architecture Review Board approval before the change they
describe merges to `main`.

| ADR | Title | Status | ARB ticket |
| --- | --- | --- | --- |
| [0001](0001-pas-target-architecture.md) | PAS target architecture: off COBOL/CICS/DB2 onto Java 21, PostgreSQL and Kafka | Proposed | ARB-TBD — to be created ([submission](arb/0001-submission.md)) |

## Operations

The [AB-530 cutover and rollback runbooks](../runbooks/cutover.md) operationalize the
strangler routing and migration decisions in ADR 0001. See the
[decommission runbook](../modernization/decommission.md) for guarded source and mainframe retirement.

## Target state and pending items

The Java 21 policy and batch services, PostgreSQL schemas, Kafka outbox, JWT scopes,
per-capability routing, migration, reconciliation, and parity tests implement the core
D1–D11 target. Operational cutover and rollback automation is rehearsed locally; production
cutover, hypercare, business/security sign-offs, and ARB approval remain pending. Source-tree
and mainframe retirement, the final `pas-legacy-final` tag, DB2 archive/restore evidence, and
MIPS savings confirmation also remain pending. No person is assigned by name until ownership
is confirmed.

## Writing a new ADR

1. Copy [`template.md`](template.md) (the organisation ADR template) to `NNNN-<kebab-title>.md`,
   where `NNNN` is the next free number, zero-padded to four digits.
2. Fill every section; write `N/A — <reason>` instead of deleting one, and `TBD — owner to confirm`
   for numbers nobody has yet.
3. Set status `Proposed`. Only the ARB moves an ADR to `Accepted`, `Rejected` or `Superseded`.
4. Put the ARB ticket body in `arb/NNNN-submission.md` and reference the ticket under an
   **Architecture review** heading in the PR description.
5. Add a row to the table above. When an ADR supersedes another, update both.
