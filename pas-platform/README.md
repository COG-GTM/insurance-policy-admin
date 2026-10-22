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

## Local development

From the repository root, `make local` runs verification, prepares local development files, builds
the service images, starts PostgreSQL/Kafka and both apps, and checks readiness. See the root
README for the one-command setup and examples.
