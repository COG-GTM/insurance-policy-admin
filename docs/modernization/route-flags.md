# Route flags

The gateway and scheduler use `pas-platform/routing/route-flags.json` as their
source of truth. Its `route` values are `legacy`, `new`, or `retired`; the
descriptions, ticket, and sign-off fields document the migration decision.
Each running service independently binds `pas.routing.<capability>` with values
`legacy`, `shadow`, `new`, or `retired` (the accepted alias `modern` means
`new`). `shadow` means that the gateway mirrors traffic to the new service
while the legacy implementation still answers the caller.

For `policy-inquiry`, the gateway routes `/api/v1/policies/**` to
`java-facade` while the state is `legacy`, mirrors the request to
`pas-policy-service` while `shadow`, and sends the request to
`pas-policy-service` when `new`. The legacy service remains the response source
during shadowing; do not enable mirrored writes.

Check the effective state at authenticated `GET /actuator/info`; the
`routes` object lists all capabilities and their service-level state. The
gateway configuration uses the file's route state and should be advanced only
with the corresponding service setting. See [ADR D9](../adr/0001-pas-target-architecture.md#d9-strangler-routing-and-definition-of-moved).
