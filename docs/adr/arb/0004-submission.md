# ARB submission: ADR 0004 — Retire PAS legacy messaging and Broker Portal DB2 access

## Architecture review
ADR: docs/adr/0004-legacy-integration-retirement.md
ARB ticket: TO BE CREATED (Pending: ARB — not created from AB-528, which does not write to Jira)
Triage: ARB_REQUIRED — T2, T4, T6, T8 (manual triage against rules/arb-triggers.md)
Summary: Policy events gain versioned JSON Schemas, a DocumentRequested topic and per-version
policy snapshots; Kafka→legacy-queue bridges (FileNet, Underwriting) with DLT/replay run inside
pas-policy-service; the Broker Portal reads through the inquiry API instead of a DB2 linked server.
Exceptions requested: none
Reviewers needed: Integration/Data, Security (+ Platform for T8)

## Pending sign-offs
- Pending: ARB
- Pending: Document/FileNet team (queue, transport, document output)
- Pending: Underwriting team (queue consumers)
- Pending: Broker Portal team (query list, API approach)
- Pending: Security (portal OIDC client, `batch:operate` operators, MQ credentials)
- Pending: Mainframe team (decommission list)
