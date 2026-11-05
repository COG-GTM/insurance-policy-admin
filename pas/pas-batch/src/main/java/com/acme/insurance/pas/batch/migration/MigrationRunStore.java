package com.acme.insurance.pas.batch.migration;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes migration runs, sync counts and reconciliation results to acmeins_shadow (Flyway V90) on
 * the PostgreSQL connection. The shadow schema exists on PostgreSQL only, so the statements may use
 * PostgreSQL syntax; they are never run against DB2.
 */
public class MigrationRunStore {

  /** Direction of a run, as stored in acmeins_shadow.migration_run. */
  public enum Direction {
    DB2_TO_PG,
    PG_TO_DB2,
    NONE
  }

  private final JdbcClient pg;

  public MigrationRunStore(JdbcClient pg) {
    this.pg = pg;
  }

  public long start(String jobName, Long jobExecutionId, Direction direction, LocalDate date) {
    return pg.sql(
            """
            INSERT INTO acmeins_shadow.migration_run
                   (job_name, job_execution_id, direction, business_date, status)
            VALUES (:job, :execution, :direction, :date, 'RUNNING')
            RETURNING run_id
            """)
        .param("job", jobName)
        .param("execution", jobExecutionId)
        .param("direction", direction.name())
        .param("date", date)
        .query(Long.class)
        .single();
  }

  public void restart(long runId, Long jobExecutionId) {
    pg.sql(
            "UPDATE acmeins_shadow.migration_run SET status = 'RUNNING', job_execution_id = :e,"
                + " finished_at = NULL WHERE run_id = :id")
        .param("e", jobExecutionId)
        .param("id", runId)
        .update();
  }

  public void finish(long runId, String status) {
    pg.sql(
            "UPDATE acmeins_shadow.migration_run SET status = :status, finished_at = localtimestamp"
                + " WHERE run_id = :id")
        .param("status", status)
        .param("id", runId)
        .update();
  }

  public void recordSync(long runId, MigrationTable table, TableSynchronizer.Result result) {
    pg.sql(
            """
            INSERT INTO acmeins_shadow.sync_table_result
                   (run_id, table_name, source_rows, inserted, updated, deleted)
            VALUES (:run, :table, :rows, :ins, :upd, :del)
            ON CONFLICT (run_id, table_name) DO UPDATE
               SET source_rows = EXCLUDED.source_rows,
                   inserted = acmeins_shadow.sync_table_result.inserted + EXCLUDED.inserted,
                   updated = acmeins_shadow.sync_table_result.updated + EXCLUDED.updated,
                   deleted = acmeins_shadow.sync_table_result.deleted + EXCLUDED.deleted
            """)
        .param("run", runId)
        .param("table", table.name())
        .param("rows", result.sourceRows())
        .param("ins", result.inserted())
        .param("upd", result.updated())
        .param("del", result.deleted())
        .update();
  }

  public void recordReconciliation(long runId, ReconciliationReport report) {
    pg.sql(
            "UPDATE acmeins_shadow.migration_run SET record_differences = :count WHERE run_id = :run")
        .param("count", report.differenceCount())
        .param("run", runId)
        .update();
    pg.sql("DELETE FROM acmeins_shadow.recon_difference WHERE run_id = :run")
        .param("run", runId)
        .update();
    pg.sql("DELETE FROM acmeins_shadow.recon_check WHERE run_id = :run")
        .param("run", runId)
        .update();
    for (ReconciliationReport.Check check : report.checks()) {
      pg.sql(
              """
              INSERT INTO acmeins_shadow.recon_check
                     (run_id, check_id, kind, table_name, dimension, metric, db2_value, pg_value, status)
              VALUES (:run, :id, :kind, :table, :dimension, :metric, :db2, :pg, :status)
              """)
          .param("run", runId)
          .param("id", check.id())
          .param("kind", check.kind().name())
          .param("table", check.table().name())
          .param("dimension", check.dimension())
          .param("metric", check.metric())
          .param("db2", check.db2Value())
          .param("pg", check.pgValue())
          .param("status", check.matches() ? "MATCH" : "DIFF")
          .update();
    }
    for (ReconciliationReport.Difference difference : report.differences()) {
      pg.sql(
              """
              INSERT INTO acmeins_shadow.recon_difference (run_id, table_name, record_key, difference)
              VALUES (:run, :table, :key, :kind)
              ON CONFLICT DO NOTHING
              """)
          .param("run", runId)
          .param("table", difference.table().name())
          .param("key", difference.recordKey())
          .param("kind", difference.kind().name())
          .update();
    }
  }

  public String systemOfRecord() {
    return pg.sql("SELECT system_name FROM acmeins_shadow.system_of_record WHERE id = 1")
        .query(String.class)
        .single();
  }

  public void setSystemOfRecord(String system, String changedBy, String reason) {
    pg.sql(
            "UPDATE acmeins_shadow.system_of_record SET system_name = :s, changed_by = :by,"
                + " reason = :reason, changed_at = localtimestamp WHERE id = 1")
        .param("s", system)
        .param("by", changedBy)
        .param("reason", reason)
        .update();
  }
}
