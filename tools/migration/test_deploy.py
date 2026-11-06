"""Keeps the migration alert rules reachable with the CronJob settings (AB-529)."""

import re
import unittest
from pathlib import Path

DEPLOY = Path(__file__).resolve().parents[2] / "pas" / "deploy" / "migration"


def cronjob(name):
    for doc in (DEPLOY / "cronjobs.yaml").read_text().split("\n---\n"):
        if re.search(rf"^  name: {re.escape(name)}$", doc, re.M):
            return doc
    raise AssertionError(f"CronJob {name} not found")


def history_limit(doc, field):
    match = re.search(rf"^  {field}: (\d+)$", doc, re.M)
    return int(match.group(1)) if match else 1  # Kubernetes default for failedJobsHistoryLimit


class DeployTest(unittest.TestCase):
    def test_delta_sync_alert_needs_consecutive_failures_the_cronjob_retains(self):
        rules = (DEPLOY / "alerts.yaml").read_text()
        rule = rules.split("- alert: PasDeltaSyncFailing", 1)[1].split("- alert:", 1)[0]
        newest = int(re.search(r"topk by \(namespace\) \((\d+), kube_job_created", rule).group(1))
        threshold = int(re.search(r"\) >= (\d+)\s*$", rule.split("labels:")[0]).group(1))
        self.assertEqual(newest, threshold, "all of the newest runs must have failed")
        delta = cronjob("pas-migration-delta-sync")
        self.assertGreaterEqual(history_limit(delta, "failedJobsHistoryLimit"), threshold)
        self.assertGreaterEqual(history_limit(delta, "successfulJobsHistoryLimit"), threshold)

    def test_daily_reconciliation_catches_up_before_comparing(self):
        recon = cronjob("pas-migration-reconciliation")
        self.assertIn("{name: PAS_BATCH_JOB, value: migrationReconciliation}", recon)
        self.assertIn('{name: PAS_MIGRATION_RECONCILE_CATCH_UP, value: "true"}', recon)


if __name__ == "__main__":
    unittest.main()
