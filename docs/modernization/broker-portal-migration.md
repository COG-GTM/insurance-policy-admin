# Broker Portal migration (AB-528)

## Current-state discovery and API mapping

The linked-server tables, SQL queries, refresh cadence, and SQL Server principal are not recorded
in this repository (OQ-16). The portal team must inventory them and sign off on this mapping:

| Portal use | Replacement |
| --- | --- |
| Entitled policy list | `GET /api/v1/policies` with `agentCode`, `branchCode`, `status`, `policyType`, `page`, and `size` |
| Policy details | `GET /api/v1/policies/{policyNumber}` |
| Coverages | `GET /api/v1/policies/{policyNumber}/coverages` |

Every query applies `book`, `agent_id`, and `branch_codes` entitlements before filtering, counting,
or paging. The list is ordered by policy number; page size is limited to 200. Direct DB2/JDBC access
from the portal is not part of the replacement.

## OAuth2 client credentials

Register a Broker Portal client for the policy API and request `policy:read`. Tokens must include
the portal's authorized `book`, `agent_id`, and/or `branch_codes` claims. The broker's object-level
entitlement must be verified before cutover; unentitled policies intentionally appear as 404.

## Cutover

1. Portal team inventories linked-server tables and queries and maps every use to the endpoints above.
2. Configure and test the client credentials and claims against non-production data.
3. Set `PAS_ROUTE_BROKER_PORTAL_READ=new` after portal-team acceptance; the route defaults to legacy.
4. Monitor API authorization errors and latency, then retire the linked-server dependency only after
   consumer sign-off.

The read-replica alternative is rejected per ADR P6: it duplicates authorization and consistency
responsibility and does not remove the linked-server integration. Portal-team sign-off is pending.
