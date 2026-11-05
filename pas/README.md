# PAS target platform (`pas/`)

Java 21 / Spring Boot 3.5 replacement for the COBOL/CICS/DB2 Policy Administration System, built
incrementally under epic AB-516 next to the legacy trees (`cobol/`, `jcl/`, `java-facade/`, `sql/`),
which stay unchanged. Architecture: [ADR 0001](../docs/adr/0001-pas-target-architecture.md).

## Quick start

Needs JDK 21, Maven 3.9+, Docker (with Compose v2), GNU make and openssl. From the repository root:

```bash
make pas            # build, all checks and tests, then start the local stack and smoke-test it
```

If JDK 21 is not your default, pass it: `make pas PAS_JAVA_HOME=/path/to/jdk-21`.

| Target | What it does |
|---|---|
| `make pas` | `pas-verify` then `pas-up`: the one command for a fresh clone |
| `make pas-verify` | `mvn verify`: Spotless format check, enforcer rules, SpotBugs, unit tests, Testcontainers integration tests (PostgreSQL 16), ArchUnit module rules |
| `make pas-format` | Reformats Java sources (google-java-format) |
| `make pas-up` | Creates `.env` (random local DB password), builds the jars and images, starts PostgreSQL, Kafka and `pas-policy-service`, waits for readiness, runs `pas/scripts/smoke.sh` |
| `make pas-smoke` | Smoke test of a running stack |
| `make pas-down` | Stops the stack (the database volume is kept; `docker compose down -v` drops it) |
| `make pas-images` | Builds both container images |
| `make pas-scan` | Trivy scan of both images; HIGH/CRITICAL findings fail unless accepted in `pas/.trivyignore` |
| `make parity` | COBOL characterization harness: re-runs the COBOL fixtures under GnuCOBOL against the golden files, then `pas-parity` checks Java against them to the cent ([parity/README.md](../parity/README.md)) |

One-shot batch run against the local stack: `docker compose --profile batch run --rm pas-batch`.

## Module layout (ADR 0001 D1)

| Module | Kind | Contents | Ticket | May depend on |
|---|---|---|---|---|
| `pas-domain` | library | Policy, coverage, premium, endorsement, decision types; money as `BigDecimal` | AB-521/522 | nothing |
| `pas-rating` | library | PREMBAT `3200-CALCULATE-PREMIUM` port | AB-522 | `pas-domain` |
| `pas-underwriting` | library | UNDWRT score and bands | AB-525 | `pas-domain` |
| `pas-persistence` | library | Repositories, Flyway migrations | AB-521 | `pas-domain` |
| `pas-events` | library | Event schemas, transactional outbox | AB-528 | `pas-domain` |
| `pas-policy-service` | application | REST API, security, policy use cases (SVC-01) | AB-524 | all libraries |
| `pas-batch` | application | Spring Batch premium run and extracts (SVC-02) | AB-523/527 | all libraries, never `pas-policy-service` |
| `pas-architecture-tests` | tests | ArchUnit rules over all modules | AB-519 | - |
| `pas-parity` | tests | Java adapters run against the COBOL characterization golden files ([parity/](../parity/README.md)) | AB-520 | library modules (test scope) |

The rules are enforced twice: the Maven enforcer (`bannedDependencies`) fails the build on a forbidden
module dependency, and `pas-architecture-tests` checks the compiled classes (layer rules, no framework
types in `pas-domain`, no direct `ACMEINS.CLAIMS` access). Every class lives in the package of its module
(`com.acme.insurance.pas.<module>`).

## Baseline provided by AB-519

- **Runtime:** Java 21, Spring Boot 3.5, virtual threads, graceful shutdown. Executable jars are
  `*/target/*-exec.jar`; the plain jar stays the main Maven artifact.
- **Security (D5):** deny by default. Every API request is rejected until AB-524 adds the OAuth 2.0
  resource server; no in-memory users, no HTTP basic, no form login, no sessions. No credential defaults
  anywhere: the datasource comes from `PAS_DB_URL`, `PAS_DB_USERNAME`, `PAS_DB_PASSWORD`.
- **Observability (D8):** structured JSON logs (Elastic Common Schema) on stdout with `traceId`, `spanId`
  and `correlationId` (`X-Correlation-Id`, generated when absent); `/actuator/health/liveness`,
  `/actuator/health/readiness` (includes the database), `/actuator/info` and `/actuator/prometheus` on
  the separate management port 8081, which is not routed through ingress. Health details are hidden
  and no other actuator endpoint is exposed. OpenTelemetry tracing via Micrometer Tracing.
- **Database (D3):** PostgreSQL 16; Flyway `V1` creates the empty `acmeins` schema. Conventions and the
  per-ticket version ranges: [pas-persistence/README.md](pas-persistence/README.md).
- **Messaging (D4):** Kafka (KRaft, single node) in the local stack for the outbox work in AB-528;
  topic auto-creation is off.
- **Images:** one `pas/Dockerfile` for both deployables: layered Spring Boot jar on
  `eclipse-temurin:21-jre-alpine` (pinned by digest, bumped by Dependabot), non-root user `10001`.

## Configuration

| Variable | Used by | Default |
|---|---|---|
| `PAS_DB_URL`, `PAS_DB_USERNAME`, `PAS_DB_PASSWORD` | both | none (required) |
| `PAS_HTTP_PORT` / `PAS_MANAGEMENT_PORT` | policy service | 8080 / 8081 |
| `PAS_LOG_FORMAT` | both | `ecs` (also `logstash`, `gelf`) |
| `PAS_TRACING_SAMPLING_PROBABILITY` | policy service | 0.1 |
| `PAS_BATCH_JOB` | batch | none (no job runs) |
| `PAS_EXTRACT_STAGING_DIR` | batch (AB-527 extracts) | none (required by the extract jobs) |
| `PAS_EXTRACT_{CLAIMS,ACTUARIAL}_SFTP_URI`, `_USERNAME`, `_KEY_FILE`, `_KEY_PASSPHRASE_FILE`, `_KNOWN_HOSTS_FILE` | batch (AB-527 extracts) | none; see [docs/pas/extracts.md](../docs/pas/extracts.md) |

In shared environments the values come from the secrets manager (ADR 0001 D5); `.env` is for local
development only and is git-ignored.

## CI and repository conventions

See [docs/pas/ci-and-conventions.md](../docs/pas/ci-and-conventions.md).
