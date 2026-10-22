# PAS platform deployment examples

`k8s/premium-batch-cronjob.yaml` is an example manifest only; it is not applied by this repository
change. Replace the image tag and provision the referenced ConfigMap and Secret through the
environment's approved deployment process. The application scheduler and this CronJob are
alternatives: enable exactly one. Both use `Forbid`/job-level overlap protection to avoid concurrent
premium recalculations.
