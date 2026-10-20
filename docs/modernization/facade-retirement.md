# REST facade retirement plan (AB-524)

Status: **Proposed — pending PO sign-off.** This plan moves every consumer of `java-facade`
(Java 8 / Spring Boot 1.5, no authentication — [Q-34](quirk-register.md#q-34),
[I-09](integrations.md#i-09)) to the authenticated inquiry API in `pas-policy-service`, then
removes the facade.

> **`java-facade/` is not deleted in AB-524.** AB-530 removes the legacy trees after cutover,
> once the traffic-to-zero criterion below has held. Until then the compat gate keeps checking
> the facade against `compat/api/snapshots/policies-v1.json`.

## 1. What replaces what

| Legacy (`java-facade`) | Replacement (`pas-policy-service`) | Notes |
| --- | --- | --- |
| `GET /api/v1/policies/{policyNumber}` | same path | Same JSON fields, types, formats and nulls; asserted against the snapshot by `PolicyInquiryApiIT` |
| `GET /api/v1/policies/{policyNumber}/coverages` | same path | All coverages in `SEQUENCE_NUM` order (POLQRY's 20-row screen limit is not carried, [Q-30](quirk-register.md#q-30)) |
| — (POLQRY screen only) | `GET /api/v1/policies/{policyNumber}/holder` | New. PII masked unless the token has `policy:pii:read` |
| `GET /manage/health` (unauthenticated, plus other unsecured actuator endpoints) | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Only the health probes are anonymous. `prometheus` needs a JWT; every other actuator endpoint is disabled |
| No auth | OIDC bearer JWT, scope `policy:read`; entitlement claims `agent_code`, `branch_code`, `book` | ADR 0001 D5. A policy outside the caller's entitlements returns 404, same as not found |

Contract: `pas-platform/pas-policy-service/src/main/resources/openapi/pas-policy-v1.yaml`
(drift-tested by `OpenApiDriftIT`).

**The only change consumers must make** is to send `Authorization: Bearer <access JWT>` from
the enterprise IdP with audience `PAS_OIDC_AUDIENCE` and the scopes/claims above. Response
bodies stay the same. A caller that used to read any policy now sees only entitled policies:
client credentials for a system integration need `book` = `*` (whole book) or a narrower
`agent:<code>` / `branch:<code>` list, as approved by the business owner.

## 2. Consumer inventory

The facade has no authentication, so nothing in PAS identifies its callers
([OQ-15](open-questions.md#oq-15)). The inventory below is what the repo knows; it must be
completed from gateway / proxy / network logs before the routing switch.

| # | Consumer | Evidence | Access needed | IdP client / claims | Migration status |
| --- | --- | --- | --- | --- | --- |
| C-1 | Broker Portal (read access) | Epic scope "Broker Portal read access"; ADR 0001 says it must use `pas-policy-service` | Policies of the broker's agency | per-user tokens with `agent_code` and/or `branch_code`; `policy:read` | Not started — Pending: Broker Portal team |
| C-2 | "Modern client systems" (unnamed) | facade README; [I-09](integrations.md#i-09) | TBC | TBC | Unknown — identify via OQ-15 |
| C-3 | Monitoring of `/manage/health` | facade `application.properties` | Liveness only | none (use `/actuator/health`) | Not started |
| C-4 | Any other caller seen in logs | — | — | — | To be discovered |

How to discover callers (owner: T. Nguyen + network team, OQ-15):

1. Turn on access logging at the gateway / load balancer in front of the facade for at least
   one full business cycle (month-end included). Record source IP, client cert or API key if
   any, user agent, path and status.
2. Map source addresses to applications through the CMDB, and add a row above for each.
3. For each consumer: owner, IdP client id, entitlement claims, test date and cutover date.

## 3. Routing switch

ADR 0001 already restricts the facade to gateway-only internal callers. The switch is a
gateway route flag per consumer, so each one moves (and can roll back) on its own.

1. **Deploy** `pas-policy-service` behind the gateway at a new upstream (for example
   `/pas/api/v1/policies/**`). The facade keeps serving the existing route.
2. **Shadow / compare (optional, read-only):** the gateway mirrors a sample of facade requests to
   the new service with a service token; compare status and body with
   `compat/api/check_api.py` semantics (`COMPAT_AUTH_HEADER` lets the checker send a bearer
   header). Investigate any difference other than `lastUpdated`.
3. **Per-consumer switch:** each consumer gets its IdP client, starts sending a bearer token,
   and its route flag `pas.inquiry.route.<consumer>=new` points its traffic at
   `pas-policy-service`. Rollback is flipping the flag back; the facade is still running.
4. **Default switch:** once every known consumer is on the new route, point the facade's public
   route at `pas-policy-service` as well. Callers that never got a token now get **401**: this is
   the forcing function that surfaces unknown consumers (C-4). Keep the facade running but
   unreachable from the gateway for the observation window.
5. **Block** direct network access to the facade (security group / network policy) so only
   the gateway could reach it, and only if the flag is reverted.

DB2 stays the system of record until the data migration and dual-run sign-off (AB-529). Until
then `pas-policy-service` reads from the `pas` schema populated by CDC.

## 4. Traffic-to-zero criterion

The facade can be removed (AB-530) when **all** of the following hold:

- Facade request count, measured at the gateway **and** on the facade's own access log, is
  **zero for 30 consecutive days including one month-end**, excluding the health check.
- No 401/403 spike on `pas-policy-service` from an unidentified client in the same window
  (every 401 source is either fixed or confirmed as not a consumer).
- Every row in the consumer inventory is "Migrated" with a named owner who confirmed it.
- `PolicyInquiryApiIT` and the compat gate pass against the release being deployed.
- Sign-offs recorded (below).

Then AB-530 deletes `java-facade/`, removes it from `compat/` (`compat/api/run_facade.sh`
defaults, `compat/build_modules.sh`) and the CI matrix, and records the change in
`compat/allowed-changes.json` with the ticket reference.

## 5. Sign-offs

| Role | Status |
| --- | --- |
| Product owner (decisions Q-30, Q-34, this plan) | Pending: Product owner |
| Security (auth model, entitlement claims, actuator lockdown) | Pending: Security |
| Facade owner (consumer inventory complete) | Pending: Facade owner (T. Nguyen) |
| Broker Portal team (C-1 migrated) | Pending: Broker Portal team |
| Each consumer in the inventory | Pending: Consumer owners |
| IdP team (clients, `agent_code` / `branch_code` / `book` claims issued) | Pending: IdP team |
| ARB (ADR 0001 D5 implemented as designed) | Pending: ARB |
