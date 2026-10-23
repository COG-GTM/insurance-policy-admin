# Contributing

## Branching and pull requests

Create a focused feature branch from the requested base branch and include the ticket ID in the
branch name and pull request. Keep legacy mainframe changes separate from the `pas-platform/`
scaffold. Use the repository pull request template and link follow-up work to its owning ticket.

## Formatting and tests

Run `cd pas-platform && ./mvnw spotless:apply` to format Java sources. Verify the full platform with
`cd pas-platform && ./mvnw -B verify`; unit tests use Surefire (`*Test`) and PostgreSQL integration
tests use Failsafe (`*IT`). `-DskipITs` skips integration tests when a build is intentionally split.
The compatibility checks can be run with `make compat`.

## Database migrations

Flyway migrations are append-only: never edit a migration that has been applied. The policy service
owns schema `pas`; the batch service owns schema `pas_batch` and must not migrate `pas`. Allocate
new versions after the latest migration in the owning schema, and record additions in the pull
request template. `pas_shadow` is reserved for future parity work.

## Compatibility gate

Run `make compat` before submitting changes that affect the legacy schema, data layouts, Java
facade, or API behavior. The gate checks schema and layout compatibility, security non-regression,
the Java builds, and the REST API contract.
