# Policy Administration System (PAS)

## Overview

The Policy Administration System is the **core insurance platform** for Acme Insurance, managing all policy lifecycle operations. Originally built in **1998** on IBM mainframe technology, it remains the system of record for all insurance policies.

**Maturity Level:** 0 (Legacy)

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
- **Historically no CI/CD pipeline** - Legacy modules still use manual compilation and deployment; CI now runs for the compatibility gate and `pas-platform`.

## Team Contacts

- **Mainframe Team:** mainframe-ops@acme.com
- **DBA Team:** db2-admin@acme.com
- **Java Facade:** t.nguyen@acme.com

## Repository layout

The legacy implementation remains alongside the new Java platform:

| Path | Responsibility |
|------|----------------|
| `cobol/` | Mainframe program sources and copybooks |
| `jcl/` | Batch job control language |
| `sql/ddl/` | Legacy DB2 schema definitions |
| `java-facade/` | Existing Java 8 / Spring Boot 1.5 read-only facade |
| `compat/` | Schema, layout, security, and API compatibility checks |
| `pas-platform/` | Java 21 / Spring Boot platform services |

`pas-platform/` currently contains these Maven modules:

| Module | Port | Responsibility |
|--------|------|----------------|
| `pas-common` | — | Shared scopes, claims, and schema names |
| `pas-parity` | — | COBOL golden-file runner and Java parity test support |
| `pas-policy-service` | `8080` | Policy API foundation and platform health |
| `pas-batch` | `8081` | Spring Batch metadata and batch service foundation |
| `pas-rating` | planned (AB-522) | Rating component shared by the policy service and batch |

## Getting started (pas-platform)

Install JDK 21 and Docker. From the repository root, run the one-command fresh-clone setup:

```sh
make local
```

This runs the Maven verification suite, creates a local `.env` and RSA development key pair when
needed, builds both service images, starts the local stack, and checks both readiness probes.
Individual targets are `make test`, `make dev`, and `make dev-down`. To remove the PostgreSQL data
volume as well, use `make dev-clean`.

Mint a local JWT and call the authenticated identity endpoint:

```sh
JWT=$(pas-platform/dev/dev-jwt.sh mint --scope "policy:read policy:write")
curl -H "Authorization: Bearer $JWT" http://localhost:8080/api/v1/platform/whoami
```

The development key pair and `.env` are ignored by Git. Local Compose ports can be overridden with
environment variables or `pas-platform/.env`; for example, set `PAS_POLICY_HTTP_PORT=18080` when
host port 8080 is already in use.

## CI

The `pas-platform` workflow runs module tests and Testcontainers integration tests, static analysis,
filesystem vulnerability scanning, and container builds. The compatibility workflow also runs for
pull requests targeting `devin/ab-516-v2` as well as `main`.
