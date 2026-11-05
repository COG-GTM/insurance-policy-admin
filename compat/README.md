# PAS backward-compatibility gate

Runs on every pull request (`.github/workflows/compat-gate.yml`) during the PAS
modernization (epic AB-516). It fails a PR that breaks an existing consumer or
business contract unless the change is explicitly approved.

| Check | Script | What fails |
|---|---|---|
| Build & tests | `build_modules.sh` | Any Maven module build/test failure (each built with the JDK its POM declares) |
| REST API contract | `api/run_facade.sh` → `api/check_api.py` | Changed status codes, removed fields, changed JSON types/values for `GET /api/v1/policies/{n}` and `/coverages` vs `api/snapshots/` (additive fields are fine) |
| DB schema | `check_schema.py` | Dropped tables/columns, narrowed types, new NOT NULL without default, removed constraints, `POLICY_SEQ` changes, edits to applied Flyway migrations |
| Record & file layouts | `check_layouts.py` | Copybook record length or field offset/size changes; JCL output dataset LRECL changes (Claims/Actuarial feeds) |
| Security non-regression | `check_security.py` | Added hardcoded secrets/keys, exposed actuator, H2 console outside local profile, plaintext FTP (PII additions are reported as notes) |

Run locally: `make compat` (needs JDK 8 for `java-facade` and Python 3).
`build_modules.sh` uses `JAVA_HOME_8_X64` when set, but `api/run_facade.sh`
starts the jar with the first `java` on `PATH`. On a JDK 17/21 machine
`make compat-api` then fails at startup (`InaccessibleObjectException`), so put
JDK 8 first, e.g. `PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH make compat`.
CI pins JDK 8, so this only affects local runs.

## Intentional changes
If a change is deliberate (e.g. AB-524 adds authentication), add the finding id
printed by the check to `allowed-changes.json` with the ticket and reason:

```json
{"allowed": [{"id": "api:GET /api/v1/policies/POL-00000001:status", "ticket": "AB-524", "reason": "endpoint now requires OAuth2; consumers migrated"}]}
```

Reviewers see the approval in the PR diff. When the API under test needs
credentials, set the `COMPAT_AUTH_HEADER` repository secret (`Name: value`).
When a replacement service takes over the read API, point `COMPAT_API_MODULE`
/ `COMPAT_API_START` at it; the snapshots stay the contract. Re-record snapshots
(`compat/api/run_facade.sh --record`) only with an approved contract change.

The Devin "PAS backward-compatibility gate" automation also reviews each PR and
covers what static checks can't (business-rule parity against COBOL, intent).
