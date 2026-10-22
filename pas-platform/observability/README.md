# PAS migration observability

Import `grafana/pas-migration.json` and load `prometheus/pas-migration-alerts.yml` into the
Prometheus rule set. Delta lag and alert metrics are `pas.sync.lag.seconds` and
`pas.sync.lag.alerts`; reverse lag is `pas.sync.reverse.lag.seconds`; reconciliation exports
`pas.recon.differences` and `pas.recon.last.run.timestamp`. The dashboard also shows parallel-run
untriaged and BUG counts and batch job failures.
