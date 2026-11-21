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

## Local Quickstart

Runs the REST facade and the backward-compatibility checks on a Linux machine (macOS needs GNU grep, see below). No mainframe access is needed: the `local` profile uses an in-memory H2 database seeded from `java-facade/src/main/resources/data.sql`.

### Prerequisites

- JDK 8, with `JAVA_HOME` pointing at it (the build fails on newer JDKs such as 21 because Spring Boot 1.5 cannot run there)
- Maven 3.6.1 or later (the compat scripts pass `-ntp`)
- Python 3.7 or later, `make`, `curl` and `git`
- GNU grep: `compat/build_modules.sh` reads each POM's Java version with `grep -P`, which the stock macOS grep lacks. On macOS run `brew install grep` and put `$(brew --prefix)/opt/grep/libexec/gnubin` first on `PATH`

```bash
export JAVA_HOME=/path/to/jdk8            # e.g. /usr/lib/jvm/java-8-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version                             # should report 1.8
```

If `JAVA_HOME_8_X64` is set (as on CI runners), `compat/build_modules.sh` uses it instead of `JAVA_HOME`, so unset it or point it at the same JDK 8.

### Run the REST facade

```bash
cd java-facade
mvn -B package
java -jar target/pas-facade-1.0.0.jar --spring.profiles.active=local --server.address=127.0.0.1
```

In another terminal:

```bash
curl http://localhost:8080/manage/health
curl http://localhost:8080/api/v1/policies/POL-00000001
curl http://localhost:8080/api/v1/policies/POL-00000001/coverages
```

The H2 console is at http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:pasdb`, user `sa`, empty password). Tables are in schema `ACMEINS`, so qualify them, e.g. `SELECT * FROM ACMEINS.POLICIES`.

`--server.address=127.0.0.1` keeps the facade on loopback: the `local` profile has no authentication on the API, the Actuator endpoints under `/manage`, or the H2 console. Without `--spring.profiles.active=local` the facade uses the DB2 settings in `application.properties` (`mainframe.acme.local`), which only resolve on the Acme network.

### Run the compatibility checks

From the repository root of a `git clone`, with `JAVA_HOME` set as above. The checks compare your working tree, uncommitted edits included, with the merge base of `HEAD` and `origin/main`, so the clone needs that ref and enough history to reach the merge base (fetch more history if a shallow clone fails). If your main remote has another name, set it, e.g. `export COMPAT_BASE_REF=upstream/main`:

```bash
make compat
```

This runs the schema, layout and security checks, `mvn verify` for `java-facade`, and the REST contract check on port 18080. That check starts the facade under the `local` profile on all interfaces, with no authentication, while it runs; to keep it on loopback, add the bind address through `COMPAT_API_START`:

```bash
COMPAT_API_START="java -jar java-facade/target/pas-facade-1.0.0.jar --spring.profiles.active=local --server.port=18080 --server.address=127.0.0.1" make compat
```

See [compat/README.md](compat/README.md) for details.

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

## Team Contacts

- **Mainframe Team:** mainframe-ops@acme.com
- **DBA Team:** db2-admin@acme.com
- **Java Facade:** t.nguyen@acme.com
