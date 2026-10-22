# PAS Platform

The platform is a Java 21 / Spring Boot 3.5 Maven reactor. It is designed to grow alongside the
legacy COBOL, JCL, DB2 DDL, and Java facade; it does not replace or modify those sources.

## Modules

| Module | Package | Port | Purpose |
|--------|---------|------|---------|
| `pas-common` | `com.acme.insurance.pas.common` | — | Shared domain, copybook, PII, security, and schema types; optional Boot/Logback adapters with no mandatory Spring dependency |
| `pas-rating` | `com.acme.insurance.pas.rating` | — | PREMBAT-compatible premium calculation, rating parameter access, and configurable factor sets |
| `pas-parity` | `com.acme.insurance.pas.parity` | — | Test-scope COBOL golden-file runner, comparators, and known-difference validation |
| `pas-policy-service` | `com.acme.insurance.pas.policy` | `8080` | Policy HTTP service and policy schema ownership |
| `pas-batch` | `com.acme.insurance.pas.batch` | `8081` | Spring Batch HTTP service and batch metadata schema ownership |

### Batch jobs

`pas-batch` provides the restartable premium recalculation, daily Claims policy/coverage extracts,
and monthly Actuarial exposure extract. The premium job reads active policies from `pas`; its route
defaults to `legacy`, `shadow` writes to `pas_shadow`, and `new`/`retired` write to `pas`. Premium
report files are isolated by job execution. Extract jobs write per-execution files under
`PAS_BATCH_EXTRACT_WORK_DIR` and deliver with pinned-host-key, public-key-only SFTP when the
corresponding `PAS_ROUTING_*` route is `new`. Scheduling is disabled by default. See the
[`premium-batch.md`](../docs/runbooks/premium-batch.md) and
[`extracts.md`](../docs/modernization/runbooks/extracts.md) runbooks for CLI, configuration and
operational steps.

The parity module is used from test scope by consumers. Run
`make parity` from the repository root to check COBOL fixtures and exercise the Java parity
adapters. See [PREMBAT rating](../docs/modernization/rating.md) for calculation and factor-mode
semantics.

## Configuration

| Variable | Services | Default / requirement | Purpose |
|----------|----------|-----------------------|---------|
| `PAS_DB_URL` | Both | `jdbc:postgresql://localhost:5432/pas` | PostgreSQL JDBC URL |
| `PAS_DB_USERNAME` | Both | Required | Database user |
| `PAS_DB_PASSWORD` | Both | Required | Database password |
| `PAS_KAFKA_BOOTSTRAP_SERVERS` | Both | `localhost:9092` | Kafka bootstrap address, reserved for future messaging |
| `PAS_RATING_FACTOR_MODE` | Both | `legacy` | `legacy` always uses the LEGACY factor set; `tables` uses the active rating factor set |
| `PAS_JWT_JWK_SET_URI` | Default profile | Required | Remote JWT JWK set |
| `PAS_DEV_JWT_PUBLIC_KEY` | `local` profile only | Required | Public key file path for local RS256 JWT validation |

The `local` profile reads `application-local.yml` and the default profile uses the JWK set URI from
`application-default.yml`. Do not use development key material outside local development.

## Database schemas and Flyway

`pas-policy-service` migrates only `pas`; `pas-batch` migrates only `pas_batch`. The first policy
migration creates the empty `pas` schema. The first batch migration is derived from the managed
Spring Batch PostgreSQL metadata script and creates the metadata tables in `pas_batch`.

Never edit an applied migration. Allocate future versions within their owning schema and coordinate
cross-service schema changes. Schema `pas_shadow` is reserved for parity work.

See the [PAS target data model](../docs/modernization/data-model.md) for the business schema,
migration, rating-reference, and coexistence details.

## PII keys

The policy service requires `PAS_PII_ENCRYPTION_KEY_ID`, `PAS_PII_ENCRYPTION_KEY_VERSION`,
`PAS_PII_ENCRYPTION_KEY`, and `PAS_PII_BLIND_INDEX_KEY`. Use `make dev-env` to generate separate
local-only keys; production keys must come from the platform secrets store. Batch does not receive
PII keys. See [PII handling](../docs/security/pii-handling.md).

## Observability

Both services expose liveness and readiness health probes and Prometheus metrics:

| Endpoint | Access |
|----------|--------|
| `/actuator/health/liveness` | Public |
| `/actuator/health/readiness` | Public; includes the database |
| `/actuator/prometheus` | JWT required |
| `/actuator/info` | JWT required |

Only `health`, `info`, and `prometheus` are exposed over HTTP. Console logs use ECS structured
formatting and metrics include the service name as the `application` tag.

## Security

The HTTP services are stateless OAuth2 JWT resource servers. All routes require authentication
except the Actuator health endpoint. Default security headers are retained. CSRF protection is
required only for unsafe cookie-authenticated requests; bearer-token APIs remain stateless.

For local development, use `dev/dev-jwt.sh keygen` and `dev/dev-jwt.sh mint`. Keep `.dev-keys/` and
`.env` local; they are ignored by Git.

## New business and underwriting (AB-525)

`POST /api/v1/policies` requires `policy:write`. Underwriting decisions use
`POST /api/v1/policies/{policyNumber}/underwriting-decisions` with
`underwriting:decide`; listing uses the same path with `GET` and `policy:read`; overrides use
`POST /api/v1/policies/{policyNumber}/underwriting-decisions/{decisionId}/override` with
`underwriting:override`. All routes also enforce the caller's `book`, `agent_id`, and
`branch_codes` entitlements.

The `new-business` and `underwriting` route flags default to `legacy`; legacy and retired routes
return 503. The `local` profile enables new mode and selects fixture claims history. In REST mode,
configure `PAS_CLAIMS_REST_BASE_URL`, `PAS_CLAIMS_TOKEN_URL`, `PAS_CLAIMS_CLIENT_ID`, and
`PAS_CLAIMS_CLIENT_SECRET`; a missing claims base URL is a startup error. Local tokens can be
minted with `./dev/dev-jwt.sh mint --scope "policy:write underwriting:decide
underwriting:override policy:read" --claim book=AGENT --claim agent_id=AGT001
--claim branch_codes=B001`.

The transactional outbox publishes to `pas.policy.events.v1` and
`pas.underwriting.events.v1`. Set `PAS_KAFKA_TOPIC_PARTITIONS` and `PAS_KAFKA_TOPIC_REPLICAS` to
configure topic creation. The API, claims adapter and outbox operational guidance is in
[`docs/modernization/runbooks/new-business-underwriting.md`](../docs/modernization/runbooks/new-business-underwriting.md).

## Policy inquiry API (AB-524)

`GET /api/v1/policies/{policyNumber}` and
`GET /api/v1/policies/{policyNumber}/coverages` preserve the facade's v1
response contract. `GET /api/v1/policies/{policyNumber}/policyholder` provides
the POLQRY screen view. All three require `policy:read` plus an object-level
entitlement: `book=ALL`, a matching `agent_id`, or a matching value in
`branch_codes`. Claims are trimmed (Q-32, ADR D7). Agent and branch codes are
stored uppercase legacy DB2 values, written only by the sync job, and JWT
claims are upper-cased before SQL comparison; a non-uppercase stored code fails
closed with 404, never open. Missing or nonmatching entitlements are
deliberately indistinguishable from an unknown policy (404).

The policyholder endpoint returns `Cache-Control: no-store`. Without `pii:read`,
SSN and tax ID are masked, date of birth is withheld (birth year remains
available), and credit score is omitted. `pii:read` returns the full regulated
values for authorized screen users (Q-33); nullable encrypted columns remain
supported (Q-26). DTOs and response bodies must not be logged.

The `policy-inquiry` route setting follows ADR D9. Production defaults to
`legacy`; local and integration profiles use `new`. The service returns an
empty 503 while the route is `legacy`. Effective capability states are
available from authenticated `/actuator/info` in the `routes` section. The
gateway/scheduler route source is
[`routing/route-flags.json`](routing/route-flags.json); see
[`docs/modernization/route-flags.md`](../docs/modernization/route-flags.md).

For local use, run `make dev-env`, start PostgreSQL with `docker compose up -d
--wait postgres` from this directory, and run the service with
`--spring.profiles.active=local --server.port=18095
--pas.dev.seed-facade-data=true --pas.routing.policy-inquiry=new`. Mint an
entitled token and call the service:

```sh
jwt="$(./dev/dev-jwt.sh mint --scope policy:read --claim book=ALL)"
curl -H "Authorization: Bearer $jwt" \
  http://localhost:18095/api/v1/policies/POL-00000001
```

Swagger UI is enabled only in the
local profile at `/swagger-ui/index.html`; `/v3/api-docs.yaml` requires
authentication. Regenerate the committed spec with
`cd pas-policy-service && ../mvnw -B -ntp -Dit.test=OpenApiSpecIT
-Dpas.openapi.write=true verify`.

Run `make compat-api-modern` from the repository root to start PostgreSQL,
build the policy service, load facade-equivalent local data, and compare the
modern API against the committed v1 snapshots.

## Local development

From the repository root, `make local` runs verification, prepares local development files, builds
the service images, starts PostgreSQL/Kafka and both apps, and checks readiness. See the root
README for the one-command setup and examples.
