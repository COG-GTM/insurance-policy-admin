# Java facade retirement plan (AB-524)

## Consumer inventory

Integration I-10 is the known REST facade path: modern client systems call
`java-facade` over HTTP, and OQ-19 records that the caller list is unknown.
Known or suspected consumers include the unspecified modern clients, the
Broker Portal (whose linked-server integration is I-03 and may overlap), and
CSR/underwriter tooling associated with the POLQRY screen. Discover consumers
before changing the public entry point by checking gateway and facade access
logs, correlating user agents and source networks, and reviewing DB2
`PASFACAD` connection accounting with mainframe operations.

## Migration and retirement

1. Inventory and identify every caller. Issue OAuth2 clients with the required
   `policy:read` scope and per-consumer `book`, `agent_id`, or `branch_codes`
   entitlement claims; do not grant whole-book access by default.
2. Put the gateway in front of the facade as described by ADR D7. Preserve the
   v1 paths and validate responses against
   [`compat/api/snapshots/policies-v1.json`](../../compat/api/snapshots/policies-v1.json).
3. Advance `policy-inquiry` from `legacy` to `shadow` to `new` using the
   [route-flags process](route-flags.md). While shadowing, compare production
   responses and reconciliation results against the facade; require no
   unexplained differences. Verify contract parity, obtain business, capability,
   and security sign-off, and have dashboards, alarms, and a rehearsed rollback
   runbook before declaring the capability moved (ADR D9).
4. Move consumers to the gateway URL; do not point consumers directly at the
   service. Keep an explicit rollback path to the facade and rehearse flag-back.
5. Watch gateway request counts for routes to `java-facade` and facade
   `http_server_requests` metrics/access logs. Retirement requires both to be
   zero for 30 consecutive days, including a month-end cycle. If traffic
   returns or compatibility checks fail, route back to the facade and resolve
   the consumer or data-parity issue before resuming.
6. Delete `java-facade/` only in AB-530, after the zero-traffic criterion and
   owner sign-off. This AB-524 change does not delete it.

| Retirement criterion | Status |
|---|---|
| Consumer inventory and client entitlements completed | Pending |
| Gateway shadow comparison and contract parity accepted | Pending |
| Facade traffic at zero for 30 consecutive days including month-end | Pending (outside code) |
| AB-530 deletion approved | Pending |
