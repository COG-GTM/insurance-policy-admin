# Extract cutover plan (AB-527)

No cut-over date is set. Each consumer moves only after production samples and five consecutive
matching runs are available, the consumer confirms ingestion, and the Claims/Actuarial owners sign
off. During parallel comparison, set the route to `legacy`: the new job generates local files but
does not deliver them or create delivery rows.

| Consumer | Current / target feed | Cutover status |
|---|---|---|
| Claims | Daily policy and coverage extracts at `/claims/import/policy_extract.dat` and `/claims/import/coverage_extract.dat` | Date TBD; ingest confirmation Pending |
| Actuarial | Monthly exposure CSV at `/actuarial/data/exposure_monthly.csv` | Date TBD; ingest confirmation Pending |
| Broker Portal refresh | No file is produced; refresh is listed in the DAILY-EXTRACT JCL header only (Q-39). Reads move to the AB-528 read API. | Not an extract cutover; coordinate with AB-528 |

## Preconditions

1. Claims and Actuarial SFTP accounts, public keys and remote directories are provisioned.
2. Each target's host key is pinned in its configured known-hosts file; the receiving owner validates
   the endpoint.
3. The v1 layout is confirmed against production samples. Complete five consecutive byte-matching
   runs for each consumer; record and approve any Q-46 ordering differences.
4. Both consumer owners confirm they validate `.sha256` and can accept an atomic overwrite of the
   unchanged remote file names. Claims must wait for both policy and coverage `.done` markers
   before ingesting either file; Actuarial waits for the exposure marker.
5. Confirm `pas.routing.daily-extract=new` and/or
   `pas.routing.monthly-exposure=new` for the approved capability. `retired` prevents launch;
   `legacy` generates comparison output without delivery.

## Rollback

Set the affected route back to `legacy` to stop SFTP delivery while keeping local extract generation
available for comparison. Re-enable the CA-7 JCL jobs PAS0100 (Claims) and PAS0200 (Actuarial) using
the approved operations procedure. Keep the remote names and consumer pickup contract unchanged
during rollback.

The route configuration is owned by this ticket pending coordinator reconciliation with
pas-common `RouteFlags` (AB-524).
