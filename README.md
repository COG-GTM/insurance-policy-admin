# Policy Administration System (PAS)

## Overview

The Policy Administration System is the **core insurance platform** for Acme Insurance, managing all policy lifecycle operations. Originally built in **1998** on IBM mainframe technology, it remains the system of record for all insurance policies.

**Maturity Level:** 0 (Legacy)

## Repository layout

| Area | Contents |
|---|---|
| Legacy | `cobol/`, `jcl/`, `sql/`, `java-facade/` |
| New Java codebase | `pas/` — `pas-observability`, `pas-policy-service`; planned `pas-domain` (AB-521), `pas-rating` (AB-522), and `pas-batch` / Spring Batch (AB-523) |
| Compatibility | `compat/` — backward-compatibility checks for the legacy system |
| Architecture | `docs/adr/` — ADR 0001 target architecture (AB-518); [ADR 0002 scaffold](docs/adr/0002-pas-service-scaffold-ci-and-local-dev.md) |
| Local services | `compose.yaml` |
| GitHub workflows and templates | `.github/` |

## Getting started (new codebase)

Prerequisites: JDK 21 and Docker with the Compose plugin.

Run `make dev` from the repository root. It verifies both Maven modules, starts PostgreSQL and the policy service, waits for readiness, and prints the service URLs.

| Endpoint | URL |
|---|---|
| Application | http://localhost:8080 |
| Liveness | http://localhost:8081/actuator/health/liveness |
| Readiness | http://localhost:8081/actuator/health/readiness |
| Prometheus metrics | http://localhost:8081/actuator/prometheus |
| Build info | http://localhost:8081/actuator/info |

Use `make fmt` to format Java sources and `make down` to stop the stack and remove its local database volume. Set `PAS_DB_PASSWORD` to override the local database password; `PAS_DB_URL` and `PAS_DB_USERNAME` configure the service connection when running outside Compose. Host ports can be overridden with `PAS_HTTP_PORT` (default `8080`), `PAS_MANAGEMENT_PORT` (default `8081`), and `PAS_DB_PORT` (default `5432`).

## CI & conventions

The new codebase CI runs four required jobs: build and unit/integration tests, Spotless/SpotBugs static analysis, OSV dependency vulnerability scanning, and container image build/smoke test. The existing compatibility gate protects the legacy tree. Use the [pull request template](.github/pull_request_template.md), format with `make fmt`, and have a repository admin run `.github/scripts/protect-main.sh` to enable required checks and branch protection.

## Architecture

### Core Platform
- **Language:** COBOL 85
- **Runtime:** IBM z/OS 2.5, CICS Transaction Server 5.6
- **Database:** IBM DB2 v12 for z/OS
- **Messaging:** IBM MQ Series 9.2
- **Batch:** z/OS JES2, CA-7 job scheduling

### REST Facade (Added 2022)
- **Language:** Java 8
- **Framework:** Spring Boot 1.5.22
- **Purpose:** Read-only REST API for modern system integration
- **Deployment:** Docker container on Linux VM (not on z/OS)

## CICS Transactions

| Transaction | Program | Description |
|-------------|---------|-------------|
| PNEW | POLNEW | Create new policy |
| PRWL | POLRNW | Policy renewal |
| PEND | POLEND | Endorsement processing |
| PQRY | POLQRY | Policy inquiry (read-only) |
| PUWR | UNDWRT | Underwriting decision |

## Batch Jobs

| JCL Job | Schedule | Description |
|---------|----------|-------------|
| DAILY-EXTRACT | Daily 23:00 | Extract policy data for Claims/Broker |
| MONTHLY-EXPOSURE | 1st of month | Exposure data for actuarial models |
| PREMIUM-BATCH | Daily 01:00 | Recalculate premiums (PREMBAT program) |

## DB2 Tables

- `POLICIES` - Policy master (header)
- `COVERAGES` - Coverage/LOB detail
- `PREMIUMS` - Premium calculations
- `ENDORSEMENTS` - Policy changes
- `UNDERWRITING_DECISIONS` - UW decision audit trail
- `POLICY_HOLDERS` - Customer master

## REST API Endpoints (Facade)

```
GET /api/v1/policies/{policyNumber}            - Policy details (JSON)
GET /api/v1/policies/{policyNumber}/coverages   - Coverage list (JSON)
GET /manage/health                              - Health check
```

> **WARNING:** The REST facade is **read-only**. All policy mutations must go through CICS transactions on the mainframe.

## Integration Points

- **Claims Engine:** Nightly flat-file extract (FTP) via DAILY-EXTRACT.jcl
- **Actuarial Models:** Monthly exposure CSV via MONTHLY-EXPOSURE.jcl
- **Broker Portal:** SQL Server linked server to DB2 (read-only ODBC)
- **Document Management:** IBM MQ events trigger document creation in FileNet

## Known Limitations

1. **No real-time API for writes** - All mutations require CICS terminal or MQ message
2. **Hardcoded rating factors** - Base rates and territory factors are hardcoded in PREMBAT; should be table-driven
3. **Leap year bug** - POLRNW renewal date calculation does not handle leap years correctly
4. **No state-specific rate caps** - Using flat 15% rate increase cap instead of state-specific regulatory limits
5. **Java facade has no authentication** - Relies on network segmentation (internal VPN only)
6. **Single-threaded batch** - PREMBAT processes policies sequentially; takes ~4 hours for full book
7. **FTP file transfer** - No encryption on daily extract files (regulatory risk)

## Source Control

- **COBOL/JCL:** IBM Rational Team Concert (RTC) on z/OS
- **Java facade:** SVN (svn://svn.acme.local/pas-facade/trunk)
- **Legacy tree:** hand-built with no CI/CD pipeline. The new Java codebase has CI.

## Team Contacts

- **Mainframe Team:** mainframe-ops@acme.com
- **DBA Team:** db2-admin@acme.com
- **Java Facade:** t.nguyen@acme.com
