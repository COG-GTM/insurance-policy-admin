# Broker Portal migration (AB-528)

## What the linked server reads today

The repository confirms the connection path, but not the linked-server query contract:

- The README describes the Broker Portal integration as a SQL Server linked server to DB2 using
  read-only ODBC.
- `sql/ddl/create-tables.sql` defines `POLICIES`, `COVERAGES`, and `POLICY_HOLDERS`. These are
  plausible source tables for portal policy, coverage, and holder data, but they are hypotheses;
  the repository does not confirm that the linked-server queries read them.
- The README describes `DAILY-EXTRACT` as a daily 23:00 extract for Claims/Broker. The JCL header
  mentions a Broker Portal linked-server refresh, but no JCL step performs that refresh (Q-39).
  The linked-server refresh frequency is therefore unknown.

## Open questions

1. Which DB2 tables or views does the linked server read? `POLICIES`, `COVERAGES`, and
   `POLICY_HOLDERS` are hypotheses only.
2. What SQL queries, joins, filters, and projected columns does the portal use?
3. How often does the portal refresh or query the linked server, and is that separate from the
   documented daily extract?
4. Which SQL Server principal and DB2 auth ID are used, and what grants do they have?
5. Which PII columns are read? The DDL includes `DATE_OF_BIRTH`, `SSN_LAST4`, `TAX_ID`, and
   `CREDIT_SCORE` on `POLICY_HOLDERS`; actual linked-server access is unverified.

These unknowns are tracked as M-08/OQ-16 and must be inventoried with the Broker Portal team before
cutover.

## API mapping

The portal team must inventory its current queries and sign off on this mapping:

| Portal use | Replacement |
| --- | --- |
| Entitled policy list | `GET /api/v1/policies` with `agentCode`, `branchCode`, `status`, `policyType`, `page`, and `size` |
| Policy details | `GET /api/v1/policies/{policyNumber}` |
| Coverages | `GET /api/v1/policies/{policyNumber}/coverages` |

Every query applies `book`, `agent_id`, and `branch_codes` entitlements before filtering, counting,
or paging. The list is ordered by policy number; page size is limited to 200. Direct DB2/JDBC access
from the portal is not part of the replacement.

## OAuth2 client credentials

Register the portal as an OAuth2 client using the client-credentials grant, with the PAS IdP as the
issuer and `policy:read` as the scope. Tokens carry the portal's authorized `agent_id`,
`branch_codes`, and `book` claims. Store client credentials in the portal's secret store. Verify
the broker's object-level entitlement before cutover; unentitled policies intentionally appear as
404.

## Read-replica alternative

A direct read replica is rejected unless the portal team requests it. It would bypass the SQL
entitlement checks in Q-32 and the API's object-level authorization, response masking, and PII
encryption controls in ADR 0001 D7; it would couple the portal to the `pas` schema, the database
polling alternative rejected in ADR 0001 D6 (which identifies Broker Portal problem P6), and retain
a direct database connection. The API path and its sign-off gates follow ADR 0001 D9.

## Cutover

1. Portal team inventories linked-server tables and queries and maps every use to the endpoints above.
2. Configure and test the client credentials and claims against non-production data.
3. Advance `policy-inquiry` to `shadow` or `new` first and confirm it serves traffic. The
   `broker-portal-read` route depends on `policy-inquiry`; startup fails if `broker-portal-read` is
   `shadow` or `new` while policy inquiry does not serve traffic.
4. Set `PAS_ROUTE_BROKER_PORTAL_READ=shadow` for observation and then `new` after portal-team
   acceptance; the route defaults to legacy.
5. Monitor API authorization errors and latency, then retire the linked-server dependency only after
   consumer sign-off.
