# PAS platform: CI pipeline and repository conventions (AB-519)

## CI on every pull request

`.github/workflows/pas-ci.yml` runs on every pull request into `main` and `devin/ab-516-v3`:

| Job (status check) | What it checks |
|---|---|
| PAS build, tests & static analysis | `make pas-verify`: JDK 21 build, Spotless format check, Maven enforcer (JDK 21, Maven 3.9, module dependency rules), SpotBugs, unit tests, Testcontainers integration tests on PostgreSQL 16, ArchUnit module rules |
| PAS container images & vulnerability scan | Builds `pas-policy-service` and `pas-batch` images and scans them (OS packages and every jar) with Trivy; HIGH/CRITICAL findings fail |
| PAS local dev stack smoke test | Runs the documented start command `make pas-up` on a clean runner: PostgreSQL, Kafka and the policy service start, readiness is UP, metrics, deny-by-default, Flyway V1 and JSON logs are checked |

`.github/workflows/parity.yml` (AB-520) runs the COBOL characterization harness on the same pull requests:

| Job (status check) | What it checks |
|---|---|
| COBOL characterization golden files | `make parity-check`: harness unit tests, fixture and known-difference validation, then every fixture re-run under GnuCOBOL must reproduce the committed golden files |
| Java vs COBOL parity (cent level) | `make parity-java`: every Java adapter registered in `parity/suites.json` against the golden files; any difference not covered by a known difference fails |

See [parity/README.md](../../parity/README.md).

The existing gates keep running: compat gate (schema, layouts and security; legacy build; REST API
contract), ADR check and discovery inventory.

Dependency and vulnerability scanning:

- Trivy scans the built images, so it sees the versions that actually ship (including the security
  overrides in `pas/pom.xml`).
- Accepted findings go in `pas/.trivyignore` with the reason, the owner and an expiry date, so they
  come back for review. Security review of the current entries is pending (sign-off table below).
- `.github/dependabot.yml` opens weekly update PRs for Maven (`pas/`), the base image (a literal, digest-pinned `FROM` in `pas/Dockerfile`, so Dependabot can bump it) and the CI actions.

## Conventions

- **Formatting:** Java follows google-java-format, enforced by Spotless in `mvn verify`; run
  `make pas-format` to fix. `pas/.editorconfig` covers the other files.
- **Static analysis:** SpotBugs (max effort, medium threshold) fails the build. Exclusions live in
  `pas/config/spotbugs-exclude.xml` and each needs a reason.
- **Module rules:** see [pas/README.md](../../pas/README.md#module-layout-adr-0001-d1).
- **Flyway:** per-ticket version ranges, see
  [pas-persistence/README.md](../../pas/pas-persistence/README.md).
- **Branches and PRs:** branch `devin/<ticket>-<slug>`, title `<KEY>: <summary>`, one ticket per PR. The
  PR template (`.github/pull_request_template.md`) has the sections Ticket, What changed, How verified,
  Compat gate checklist, Flyway versions added, Follow-ups.
- **Legacy trees:** `cobol/`, `jcl/`, `java-facade/` and `sql/` are not edited; the compat gate fails any
  incompatible change.

## Branch protection on `main`

The intended protection is versioned as a GitHub ruleset in
`.github/rulesets/main-branch-protection.json`:

- changes only through pull requests with 1 approval, stale approvals dismissed, last push approved,
  review threads resolved;
- no force pushes, no deletion, no bypass actors;
- required, up-to-date status checks: the three PAS CI jobs above and the existing compat gate, ADR and
  inventory checks.

Applying it needs repository administration rights, which the automation token does not have (the
GitHub API returns `403 Resource not accessible by integration`). A repository admin applies it with:

```bash
tools/github/apply_branch_protection.sh            # dry run: shows the request and required checks
tools/github/apply_branch_protection.sh --apply    # creates or updates the ruleset
```

## Sign-offs

| Item | Owner | Status |
|---|---|---|
| Apply the `main` ruleset (required checks, reviews) | Repository admin | pending |
| Accepted vulnerabilities in `pas/.trivyignore` | Security | pending |
| CI runner, image registry and image signing for shared environments | Platform team | pending |
| Base image `eclipse-temurin:21-jre-alpine` as the approved runtime image | Security / Platform team | pending |
