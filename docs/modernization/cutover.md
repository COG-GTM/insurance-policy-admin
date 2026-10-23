# AB-530 cutover, rollback, and decommission overview

This page is an index and artifact overview, not a second operational runbook. The
step-by-step gates and procedures live in the [cutover](../runbooks/cutover.md),
[rollback](../runbooks/rollback.md), [operations handover](../runbooks/operations-handover.md),
and [decommission](decommission.md) runbooks.

The executable plans are in [`pas-platform/cutover`](../../pas-platform/cutover/README.md).
Cutover and rollback default to dry-run, require explicit execute and run-id arguments, log
step durations, and persist completed steps for resumption. Local rehearsals use Compose
ports 18080/18081 and store logs beneath `$HOME/ab530/`.
