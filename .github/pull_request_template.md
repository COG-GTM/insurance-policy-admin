## Ticket
<!-- Jira key and link, e.g. AB-519. One ticket per PR. -->

## What changed
<!-- What and why, for a reader who has not seen the diff. -->

## How verified
<!-- Commands you ran and their results, e.g. `make pas-verify`, `make pas-up`, `make compat`. -->

## Compat gate checklist
- [ ] `make compat` passes (schema, layouts, security, legacy build, REST API contract)
- [ ] Legacy trees `cobol/`, `jcl/`, `java-facade/`, `sql/` unchanged
- [ ] Any deliberate contract change is listed in `compat/allowed-changes.json` with ticket and reason
- [ ] No secrets, credentials or real PII in code, config, docs or test data

## Flyway versions added
<!-- e.g. "V10, V11 (postgresql)" or "None". Use only your ticket's allocated range. -->

## Follow-ups / pending outside code
<!-- Open questions, ARB items, pending sign-offs, follow-up tickets. -->
