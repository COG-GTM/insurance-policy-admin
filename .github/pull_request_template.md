## Summary

<!-- What does this change do? -->

## Jira ticket

AB-xxx — https://cog-gtm.atlassian.net/browse/AB-xxx

## Test evidence

- Commands run:
- CI run: <!-- link -->
- Screenshots/logs:

## Parity / backward-compat impact

- API v1 contract:
- DB2 schema:
- Copybook/file layouts:
- `compat/allowed-changes.json` entry + ticket if an intentional break:

## Architecture review

- ARB triage result:
- ARB-xxxx ticket:
- ADR link:

New services, data stores, and external dependencies need ARB sign-off before merge.

## Checklist

- [ ] `./mvnw verify` passes locally
- [ ] Spotless applied
- [ ] Tests added/updated
- [ ] No secrets or credentials committed
- [ ] `make compat-static` passes
- [ ] Compat gate green
- [ ] PAS platform CI green
- [ ] Docs/runbooks updated
