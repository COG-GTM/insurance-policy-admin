# Threat model — COG-GTM/insurance-policy-admin (Acme PAS)

_Status: drafted from the code and confirmed by the system owner (2026-10-30). Security scans start from this file; keep it current when trust boundaries, assets or accepted risks change._

## System overview
Acme Insurance's Policy Administration System: the system of record for all policies. It has a COBOL 85 core on z/OS 2.5 / CICS TS 5.6 / DB2 v12 (subsystem `DBPD`, schema `ACMEINS`), JES2/CA-7 batch, and a read-only Java 8 / Spring Boot 1.5.22 REST facade that runs in Docker on a Linux VM and connects to DB2 over JDBC (`jdbc:db2://mainframe.acme.local:5023/DBPD`). A Python CI "compat gate" (`compat/`, `.github/workflows/compat-gate.yml`) checks schema, layout, security and API backward compatibility on PRs.

## Components
| Name | Code | Notes |
|---|---|---|
| **REST facade** | `java-facade/` (`PolicyController`, `PolicyRepository`, `application*.properties`, `Dockerfile`, `pom.xml`) | `GET /api/v1/policies/{policyNumber}` and `/coverages`, plus actuator `/manage/*` and springfox swagger. **No authentication** (README limitation 5, comment in `PolicyController`). Parameterised JdbcTemplate queries. |
| **CICS online** | `cobol/programs/POLNEW,POLQRY,POLRNW,POLEND,UNDWRT.cbl`, `cobol/copybooks/*.cpy` | Transactions PNEW/PQRY/PRWL/PEND/PUWR. 3270 BMS map input → embedded static SQL. These are the only write path to DB2. |
| **Batch & file transfer** | `cobol/programs/PREMBAT.cbl`, `jcl/*.jcl` | DAILY-EXTRACT, MONTHLY-EXPOSURE, PREMIUM-BATCH. Extracts go by **plaintext FTP** (the z/OS FTP client in the JCL steps, user `ACMEFTP`) to `CLAIMSRV.ACME.LOCAL` and `ACTUARIAL.ACME.LOCAL` (README limitation 7). |
| **DB2 schema** | `sql/ddl/create-tables.sql`, `java-facade/src/main/resources/schema.sql`, `data.sql` | POLICIES, COVERAGES, PREMIUMS, ENDORSEMENTS, UNDERWRITING_DECISIONS, POLICY_HOLDERS. |
| **CI compat gate** | `compat/`, `.github/workflows/compat-gate.yml`, `Makefile` | Runs on `pull_request` to main with `permissions: contents: read`. Uses secret `COMPAT_AUTH_HEADER` in the api-contract job. |

## Trust boundaries & entry points
1. **Network → REST facade (port 8080).** The README says it is reachable only over the internal VPN, so network segmentation is the only control. Any VPN user or compromised internal host can call every endpoint, including `/manage/*` (actuator security is turned off in `application.properties`) and swagger UI.
2. **REST facade → DB2.** It connects as service ID `PASFACAD`, and the password comes from the `DB2_PASSWORD` env var. The default `changeme` is baked into both the `Dockerfile` ENV and `application.properties`. The facade's DB2 grants should be read-only, but nothing in this repo shows they are.
3. **3270 terminal users → CICS programs.** Authentication and authorisation are external (RACF/CICS security, not in the repo). Map input flows into SQL host variables and into premium/underwriting logic.
4. **z/OS batch → downstream hosts.** FTP to Claims and Actuarial, plus the Broker Portal's read-only ODBC linked server. Data crosses the network unencrypted.
5. **PR author → CI.** Workflows run PR code (`build_modules.sh`, `run_facade.sh`, `COMPAT_API_START` env override). The `pull_request` trigger doesn't pass secrets to fork PRs.

## Assets & sensitivity
**User-confirmed critical assets:** (1) policyholder PII (DOB, SSN last 4, tax ID, credit score) and (2) DB2 / FTP service credentials. Policy and premium integrity matters but was not named as critical.

- **Policyholder PII** in `POLICY_HOLDERS` / `CUSTOMER-RECORD.cpy`: `DATE_OF_BIRTH`, `SSN_LAST4`, `TAX_ID`, `CREDIT_SCORE`, plus names and addresses. This data is regulated. The facade's current queries don't select from POLICY_HOLDERS but do return `POLICYHOLDER_ID`.
- **Policy, premium and underwriting integrity** in DB2. This is the system of record and drives billing, claims and actuarial work.
- **Credentials**: DB2 `PASFACAD` password, FTP `ACMEFTP` credentials, the CI secret `COMPAT_AUTH_HEADER`.
- **Extract files**: daily policy/coverage/customer extracts and monthly exposure CSV.

## Attacker profiles (confirmed by user)
- **User-confirmed:** the REST facade is reachable **only over the internal VPN**, as the README says. The attackers that matter for it are insiders and compromised internal hosts. Internet-exposure scenarios for the facade don't apply.
- **A1 internal network user / compromised internal host** with VPN reach to the facade and to FTP traffic. This is the primary attacker.
- **A2 malicious or careless insider** with CICS terminal access (e.g. an agent or underwriter) who abuses transaction logic.
- **A3 external PR contributor** targeting CI.
- **Internet attacker**: not able to reach the facade directly if the README's VPN-only deployment holds.

## Existing controls
- Parameterised SQL in `PolicyRepository` and static embedded SQL in COBOL.
- The facade is read-only by design (only GET mappings).
- CI `check_security.py` blocks new hard-coded secrets, actuator exposure, H2 console, and plaintext FTP **in added lines only**. Local/test files are exempt.
- The workflow has `permissions: contents: read`.
- The H2 console and H2 DB are only in the `local` profile and in tests.

## Out of scope / accepted risk
- **User: nothing is out of scope or accepted. Rate everything on its merits.** The README's known limitations (no facade auth, plaintext FTP, etc.) are **not** accepted risks. Local and test profiles are in scope too.

## Open questions
- None outstanding. The user confirmed there is no infrastructure repository, so this model rests on `java-facade/Dockerfile` and the README's deployment notes.
- Finding tags: CVE (requested by the user).
