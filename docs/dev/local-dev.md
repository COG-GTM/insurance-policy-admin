# Local development

The `pas-platform` services run locally in Docker Compose with PostgreSQL and Kafka. This environment is for development only; it does not replace the legacy COBOL/CICS/DB2 system of record.

## Prerequisites

- Docker Engine and Docker Compose v2 (`docker compose`)
- JDK 21 for Maven commands and IDE runs
- GNU Make

Copy the Compose environment template before starting:

```sh
cp pas-platform/.env.example pas-platform/.env
```

Compose loads `pas-platform/.env` automatically. Its database password default is intended only for a local, disposable environment.

## Services and ports

| Service | Local address | Image | Purpose |
| --- | --- | --- | --- |
| PostgreSQL | `localhost:5432` | `postgres:16` | Policy and Spring Batch metadata storage |
| Kafka | `localhost:9092` | `apache/kafka:3.9.1` | Single-node KRaft broker for local development |
| `pas-policy-service` | `http://localhost:8080` | Built as `pas-policy-service:local` | Policy REST API |
| `pas-batch` | `http://localhost:8081` | Built as `pas-batch:local` | Spring Batch service |

The policy service uses `kafka:19092` to reach Kafka on the Compose network. Host applications use `localhost:9092`. The batch service uses PostgreSQL and does not require Kafka.

| Endpoint | Policy service (8080) | Batch service (8081) |
| --- | --- | --- |
| Liveness | `/actuator/health/liveness` | `/actuator/health/liveness` |
| Readiness (includes database) | `/actuator/health/readiness` | `/actuator/health/readiness` |
| Prometheus metrics | `/actuator/prometheus` | `/actuator/prometheus` |
| Ping | `/api/v2/ping` | — |

## Make targets

Run targets from the repository root:

| Target | Description |
| --- | --- |
| `make build` | Run Maven `verify` with tests skipped (format and static analysis checks still run). |
| `make test` | Run Maven `verify`, including unit and Failsafe integration tests. |
| `make dev-images` | Build both service images with BuildKit. If `MAVEN_SETTINGS` points to an existing file, pass it as an optional build secret. |
| `make dev-up` | Start the Compose stack, wait for health checks, then print service URLs. |
| `make dev` | Build service images, then start the stack. |
| `make dev-down` | Stop and remove the stack, named PostgreSQL volume, and orphaned Compose containers. |
| `make dev-logs` | Follow the most recent 200 lines of Compose logs. |
| `make dev-ps` | Show Compose service status. |

`make dev-down` removes the `pas-pgdata` volume and its local data. Use it when you intentionally want to reset the database.

## Configuration

Override environment variables in `pas-platform/.env`, or override Make variables on the command line. Use environment variables for the local environment rather than committing personal credentials or mirror configuration.

| Variable | Default | Used by | Description |
| --- | --- | --- | --- |
| `PAS_DB_USERNAME` | `pas` | Compose/apps | PostgreSQL and application database user. |
| `PAS_DB_PASSWORD` | `changeme` | Compose/apps | Local-only PostgreSQL and application password. |
| `PAS_SPRING_PROFILES_ACTIVE` | `local` | Compose/apps | Spring profile; `local` enables plain-text logs. Other profiles use JSON logs. |
| `PAS_POSTGRES_IMAGE` | `postgres:16` | Compose | PostgreSQL image override. |
| `PAS_KAFKA_IMAGE` | `apache/kafka:3.9.1` | Compose | Kafka image override. |
| `MVNW_REPOURL` | Empty (Maven Central) | Make, Docker builds | Alternate Maven Wrapper distribution repository base. Supply it through the environment or as a Make variable when Maven Central is unavailable. |
| `MAVEN_SETTINGS` | `$(HOME)/.m2/settings.xml` | `make dev-images` | Maven settings file passed to Docker BuildKit only when the file exists. It is not copied into an image. |
| `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX` | Unset | `make test` | Optional registry prefix for Docker images pulled by Testcontainers. |

Example for a customized local environment:

```sh
PAS_DB_USERNAME=pas PAS_DB_PASSWORD=changeme make dev
```

To use a local Maven mirror without storing its settings in the repository, set `MVNW_REPOURL` in the shell or Make invocation and provide a local settings file:

```sh
MAVEN_SETTINGS="$HOME/.m2/settings.xml" MVNW_REPOURL="<mirror-base-url>" make dev
```

## Run the policy service from an IDE

Start only the dependencies so the IDE can own the service's port:

```sh
docker compose -f pas-platform/docker-compose.yml up -d postgres kafka
```

From `pas-platform`, build/install the shared module if it is not already in your local Maven repository, then start the policy service with host-accessible dependency addresses:

```sh
./mvnw -B -pl pas-common install -DskipTests
PAS_DB_URL=jdbc:postgresql://localhost:5432/pas \
PAS_DB_USERNAME=pas \
PAS_DB_PASSWORD=changeme \
PAS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
SPRING_PROFILES_ACTIVE=local \
  ./mvnw -pl pas-policy-service spring-boot:run
```

## Tests

`make test` runs Maven `verify`: unit tests and `*IT` integration tests are run by Maven Failsafe. Integration tests use Testcontainers and require a running Docker daemon. The tests use `postgres:16-alpine`; if a registry is rate-limited, pre-pull that image or set `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX` to the configured registry mirror prefix in the shell running `make test`.

## Troubleshooting

- **A port is already in use:** stop the process or container using 5432, 9092, 8080, or 8081, or change the relevant port mapping in `pas-platform/docker-compose.yml`.
- **Image registry requests are rate-limited:** authenticate or use an approved registry mirror. Maven downloads can use `MAVEN_SETTINGS` and `MVNW_REPOURL`; Docker base images can be pre-pulled and tagged locally with the expected image name.
- **Maven cannot download the wrapper distribution:** configure `MVNW_REPOURL` in the shell or Make invocation to use an available Maven repository base. Do not commit environment-specific mirror URLs.
- **A service is unhealthy:** inspect `make dev-ps` and `make dev-logs`. The readiness endpoint includes the database check; the policy service also waits for Kafka health before starting.
- **Logs are JSON instead of plain text:** set `PAS_SPRING_PROFILES_ACTIVE=local` in `pas-platform/.env` and recreate the application containers.
- **The database needs a clean reset:** `make dev-down` removes all Compose services and the `pas-pgdata` volume. Starting again recreates the Flyway-owned `pas` and `pas_batch` schemas.
