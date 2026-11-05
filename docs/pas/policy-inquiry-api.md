# Policy inquiry API (AB-524)

Replaces CICS transaction `PQRY` (program `POLQRY`) and the Spring Boot 1.5 facade in `java-facade/`
with `pas-policy-service` (Java 21 / Spring Boot 3). Decisions follow
[ADR 0001](../adr/0001-pas-target-architecture.md) D1 (one policy service), D5 (security) and D9 (PII).
The spec is [`policy-inquiry-v1.yaml`](../../pas/pas-policy-service/src/main/resources/openapi/policy-inquiry-v1.yaml),
shipped in the jar under `openapi/`.

## 1. Endpoints

| Endpoint | Replaces | Contract |
|---|---|---|
| `GET /api/v1/policies/{policyNumber}` | facade `PolicyController.getPolicy`, POLQRY `2000-READ-POLICY` | Facade JSON, field for field |
| `GET /api/v1/policies/{policyNumber}/coverages` | facade `getCoverages`, POLQRY `4000-READ-COVERAGES` | Facade JSON, `SEQUENCE_NUM` order, all rows (the facade had no 20-row cap; POLQRY's cap only applied to its unused table, Q-24) |
| `GET /api/v1/policies/{policyNumber}/policyholder` | POLQRY `3000-READ-CUSTOMER` (read but never shown on screen, Q-24) | New, additive. Masked unless `pas.policyholder.read` |

Everything else on the API port is denied (403 with a token, 401 without).

## 2. Authentication and authorisation (ADR 0001 D5, Q-36, AB-365)

- **Tokens:** OAuth 2.0 / OIDC JWT access tokens, RS256 only. Issuer (`PAS_JWT_ISSUER`, mandatory:
  the service does not start without it), audience (`PAS_JWT_AUDIENCE`, default `pas-policy-service`),
  signature (IdP JWK set `PAS_JWT_JWK_SET_URI`, or a PEM key `PAS_JWT_PUBLIC_KEY_LOCATION` for local
  stacks and tests), `exp` required, 60 s clock skew. Invalid or missing token: 401 with
  `WWW-Authenticate: Bearer`.
- **Scopes:** `pas.policy.read` for all three endpoints (403 without it); `pas.policyholder.read` to
  see holder contact details in clear; `pas.ops.read` for `/actuator/info` and `/actuator/prometheus`.
- **Object-level entitlement:** `JwtClaimsEntitlementResolver` reads `pas_book` (`"*"` = the whole
  book, for back-office and system clients), `pas_agents` and `pas_branches` (JSON arrays or
  space-separated strings). A policy is visible when the caller holds the whole book, or its
  `AGENT_CODE` or `BRANCH_CODE` is listed. No claims means no policies. Coverages and the holder are
  only read after the policy check passes. `EntitlementResolver` is an interface so a PAS-owned
  entitlement store can replace the claims if the IdP cannot issue them (ADR 0001 risk register).
- **Cross-tenant requests answer 404 with an empty body**, identical to a missing policy, so policy
  numbers (sequential) cannot be enumerated. Denials are logged (operation, client, policy number;
  no personal data) and counted. ARB item A-1 below.
- **Management:** the management port (8081, not routed through ingress) serves only health (open,
  for probes, no details), info and Prometheus (both need `pas.ops.read`); every other actuator
  endpoint is denied. Prometheus scrapes with a client-credentials token.

Tests: `PolicyInquiryApiIT` (no token, garbage, untrusted key, wrong issuer, wrong audience,
expired, no `exp`, missing scope, agent and branch entitlement, no entitlement, cross-tenant equals
not-found, masking, overlong and injection-shaped keys, other methods and paths),
`PasPolicyServiceApplicationIT` (management lock-down), unit tests for the resolver, entitlement and
service.

## 3. Personal data

POLQRY reads the holder's id, type, names, company, phone and email. The policyholder endpoint
returns exactly those, never a RESTRICTED value (date of birth, SSN, tax id, credit score) or an
address. Without `pas.policyholder.read` the values come from `acmeins.policy_holders_masked` (V12),
so masking rules live in one place: names and company as initial + `***`, email `***@domain`, phone
null, `"masked": true`. The policy and coverage payloads carry no direct personal data (the holder
id is a pseudonymous key, unchanged from the facade). `PolicyHolderContact.toString()` prints no
personal data.

## 4. Compatibility with the facade

- `FacadeContractIT` runs the compat gate's own checker (`compat/api/check_api.py`, snapshots in
  `compat/api/snapshots/policies-v1.json` recorded from the facade) against the new service loaded
  with the facade's seed data and a whole-book token: **PASS**. The same check without a token fails
  on 401s; that authentication change is the one deliberate contract change.
- `make pas-api-contract` does the same against the running local stack.
- `compat/allowed-changes.json` is unchanged: `make compat-api` still targets `java-facade`, which is
  unchanged, so it reports no findings. When the gate is repointed at the new service (retirement
  gate G4), the resulting `API-STATUS` findings for unauthenticated requests are approved there with
  this ticket as the reason.
- Facade serialisation quirks kept (Q-524-1): `DATE` columns as `yyyy-MM-dd`, `LAST_UPDATED` as
  epoch milliseconds (zone `PAS_INQUIRY_TIMESTAMP_ZONE`, default UTC; the facade used the JVM
  default zone), null `RENEWAL_COUNT` / `RISK_SCORE` / `COINSURANCE_PCT` as 0, nulls included, 404
  with an empty body. `API_FLAG` stays informational (Q-24, FU-10).
- Known differences outside the snapshots: `Content-Type` is `application/json` (the facade sent
  `;charset=UTF-8`); Spring 6 no longer matches a trailing slash or a `.json` suffix; the facade
  trimmed leading blanks, the target strips trailing blanks only (CHAR columns are right-padded).

## 5. Consumer migration and facade retirement

See [facade-retirement.md](facade-retirement.md). Every inquiry increments
`pas_inquiry_requests_total{operation,outcome,client}` (`client` = the token's `azp` / `client_id`),
which shows each consumer arriving on the new API while gateway metrics show facade traffic draining.

## 6. Open items and ARB

| Id | Item | Owner |
|---|---|---|
| A-1 (ARB) | Approve 404 (not 403) for cross-tenant requests | ARB + Security |
| A-2 (ARB) | Approve token claims `pas_book` / `pas_agents` / `pas_branches` as the entitlement source, or decide a PAS entitlement store | ARB + Security + IdP team |
| FU-524-1 | Enterprise IdP issuer, audience, JWK set URI and client registrations per environment | Security + Platform |
| FU-524-2 | Confirm `LAST_UPDATED` zone of the facade JVM in production (epoch-millis values shift otherwise) | Facade owner |
| FU-524-3 | Approve the masked holder view for agents without `pas.policyholder.read` (which roles get the scope) | Data governance + Product Owner |
| FU-10 | Facade consumers and whether `API_FLAG='N'` policies are hidden | Product Owner + facade owner |

Flyway: none added (range V40-V49 unused; reads only).

## 7. Sign-off

| Area | Who | Status |
|---|---|---|
| Security (D5 controls, A-1, A-2, FU-524-1) | Security architect | pending |
| Data governance (holder masking, FU-524-3) | Data governance lead | pending |
| Product Owner (policyholder endpoint, FU-10) | Product Owner, Policy Administration | pending |
| Facade owner and downstream consumers (contract, retirement plan) | t.nguyen@acme.com + consumer owners | pending |
| ARB | ARB | pending |
