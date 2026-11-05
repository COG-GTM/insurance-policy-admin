# Facade retirement plan (AB-524)

Retires `java-facade/` (Spring Boot 1.5, Java 8, no authentication, Q-36) in favour of the policy
inquiry API in `pas-policy-service` ([policy-inquiry-api.md](policy-inquiry-api.md)). The module stays
byte-for-byte unchanged on the epic branch: deletion is a separate, gated change.

The gates and steps live in [`facade-retirement.json`](facade-retirement.json) and are driven by
`tools/retirement/facade_retirement.py`:

```
make facade-retirement                                   # validate the plan + unit tests + dry run
python3 tools/retirement/facade_retirement.py --execute  # refuses until every gate is approved
```

`--execute` refuses while any gate lacks `approved` + evidence, refuses on `main` and
`devin/ab-516-v3`, only changes the working tree (`git rm -r java-facade`) and never commits or pushes.

## Phases

1. **Inventory consumers (G1).** Gateway and VPN flow logs to the facade's port, plus FU-10 answers.
   Each consumer gets an owner and a target date.
2. **Register and entitle (G2).** One IdP client per consumer with `pas.policy.read` and the narrowest
   entitlement (`pas_agents` / `pas_branches`; `pas_book: "*"` only for back-office systems).
   `pas.policyholder.read` only where Data governance approves.
   Before the first consumer is routed in an environment, set `PAS_INQUIRY_TIMESTAMP_ZONE` there to
   the facade's JVM time zone (FU-524-2), otherwise `lastUpdated` epoch millis shift by the offset.
3. **Route.** The gateway (PLT-01) exposes the new service at the same paths. Consumers switch base
   URL and add the bearer token; payloads are unchanged (`FacadeContractIT`). Weighted routing is
   possible because the paths are identical, but unauthenticated callers will get 401 from the new
   service, so each consumer switches only after G2.
4. **Drain to zero (G3).** Facade requests at the gateway reach zero and stay there for 30 days while
   `sum by (client) (rate(pas_inquiry_requests_total[1d]))` shows every inventoried client.
5. **Repoint the gate (G4).** `make compat-api` runs against the new service (`pas/scripts/api-contract.sh`);
   approve the unauthenticated-status findings in `compat/allowed-changes.json` with AB-524 as reason.
   The gate only type-checks `lastUpdated` (volatile), so compare its values against facade
   responses in each environment as part of G4.
6. **Sign-off (G5)** and **rollback window (G6):** facade scaled to zero for one release cycle.
7. **Delete.** Run `--execute` on a dedicated branch, finish the manual steps (S3, S4), open a PR.

Rollback before step 7: scale the facade back up and restore its gateway route; no data is owned by
the facade (read-only on ACMEINS).

## Status

All gates pending. Facade traffic cannot drop to zero, nor the module be deleted, from this
repository alone: both need production traffic evidence and the sign-offs above.
