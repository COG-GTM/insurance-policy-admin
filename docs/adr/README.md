# Architecture Decision Records

Architecture decisions for the PAS modernization (epic AB-516) and anything else in
this repository that changes services, data stores, integrations, auth/network
boundaries or runtimes.

## Process

1. Copy [`template.md`](template.md) to `NNNN-<kebab-title>.md` using the next free number.
2. Fill every section. Unknown numbers are written as `TBD — owner to confirm before ARB`
   and listed under *Open questions*; never guess NFRs or costs.
3. Open a PR with the ADR at status **Proposed** and put the ARB submission body in
   `arb/NNNN-submission.md`. Reference the ARB ticket in the PR under an
   **Architecture review** heading.
4. Only the Architecture Review Board changes the status (`Accepted`, `Rejected`,
   `Superseded by NNNN`). A PR whose architecture is still under review does not merge to `main`
   until the ARB ticket is approved.
5. Decisions are not edited after they are accepted. Write a new ADR that supersedes the old one,
   and update both status lines.

## Index

| ADR | Title | Status | Ticket | ARB |
| --- | --- | --- | --- | --- |
| [0001](0001-pas-target-architecture.md) | PAS target architecture | Proposed | AB-518 | ARB-TBD (to be created), [submission](arb/0001-submission.md) |
| [0003](0003-db2-migration-sync-reconciliation.md) | DB2 → PostgreSQL migration, delta sync, reconciliation and parallel run | Proposed | AB-529 | ARB-TBD (to be created) |
| [0004](0004-legacy-integration-retirement.md) | Retire PAS legacy messaging and Broker Portal DB2 access | Proposed | AB-528 | ARB-TBD (to be created), [submission](arb/0004-submission.md) |
