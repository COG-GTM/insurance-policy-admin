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
- **No CI/CD pipeline** - Manual compilation and deployment

## Modernization (AB-516)

Behaviour inventory, quirk register and open questions: [docs/modernization/](docs/modernization/README.md). Check them with `make inventory`.

The new Java 21 / Spring Boot 3 platform is built in [`pas/`](pas/README.md), next to the legacy trees, which stay unchanged until cutover:

```
cobol/  jcl/  sql/       legacy COBOL/CICS programs, batch JCL, DB2 DDL (unchanged)
java-facade/             legacy Java 8 REST facade (unchanged)
pas/                     target platform (ADR 0001): Maven multi-module build
  pas-domain/              domain types, no dependencies
  pas-rating/  pas-underwriting/  pas-persistence/  pas-events/    library modules
  pas-policy-service/      REST API deployable
  pas-batch/               Spring Batch deployable
  pas-architecture-tests/  module dependency rules (ArchUnit)
docker-compose.yml       local PostgreSQL 16, Kafka and the policy service
```

Build, test and start it locally with one command (JDK 21, Maven 3.9+ and Docker required): `make pas`. If JDK 21 is not your default JDK, pass it: `make pas PAS_JAVA_HOME=/path/to/jdk-21` (on an older JDK the Maven enforcer stops the build). CI and repository conventions: [docs/pas/](docs/pas/ci-and-conventions.md).

Parity with the COBOL is proven by the characterization harness in [`parity/`](parity/README.md): the legacy programs run under GnuCOBOL against controlled fixtures, their outputs are stored as golden files, and the Java implementation must match them to the cent. Run it with `make parity`.

## Team Contacts

- **Mainframe Team:** mainframe-ops@acme.com
- **DBA Team:** db2-admin@acme.com
- **Java Facade:** t.nguyen@acme.com
