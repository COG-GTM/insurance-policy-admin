package com.acme.insurance.pas.batch.migration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Delta-sync bookkeeping in {@code pas.migration_watermarks} and the rollback pause switch in
 * {@code pas.migration_sync_control} (Flyway V7, pas-policy-service).
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shares Spring-managed collaborators (JDBC, services) by design")
public final class SyncState {

  public static final String SYNC_NAME = "DB2_TO_PAS";

  private final JdbcTemplate jdbc;

  public SyncState(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<LocalDateTime> watermark(MigrationTable table) {
    List<Timestamp> found =
        jdbc.queryForList(
            "SELECT watermark_ts FROM pas.migration_watermarks WHERE source_table = ?",
            Timestamp.class,
            table.sourceTable());
    return found.isEmpty() || found.get(0) == null
        ? Optional.empty()
        : Optional.of(found.get(0).toLocalDateTime());
  }

  public void saveWatermark(
      MigrationTable table, LocalDateTime watermark, long rowsApplied, String runId) {
    jdbc.update(
        "INSERT INTO pas.migration_watermarks"
            + " (source_table, watermark_ts, rows_applied, last_run_at, last_run_id)"
            + " VALUES (?, ?, ?, LOCALTIMESTAMP, ?)"
            + " ON CONFLICT (source_table) DO UPDATE SET"
            + " watermark_ts = GREATEST(pas.migration_watermarks.watermark_ts,"
            + " EXCLUDED.watermark_ts),"
            + " rows_applied = pas.migration_watermarks.rows_applied + EXCLUDED.rows_applied,"
            + " last_run_at = EXCLUDED.last_run_at, last_run_id = EXCLUDED.last_run_id",
        table.sourceTable(),
        watermark == null ? null : Timestamp.valueOf(watermark),
        rowsApplied,
        runId);
  }

  public boolean paused() {
    List<Boolean> paused =
        jdbc.queryForList(
            "SELECT paused FROM pas.migration_sync_control WHERE sync_name = ?",
            Boolean.class,
            SYNC_NAME);
    return !paused.isEmpty() && Boolean.TRUE.equals(paused.get(0));
  }

  /**
   * Reads the pause flag with a shared row lock inside the caller's write transaction, so a
   * concurrent {@link #setPaused} blocks until the in-flight chunk commits and no chunk commits
   * after a pause is acknowledged.
   */
  public boolean pausedLocked() {
    List<Boolean> paused =
        jdbc.queryForList(
            "SELECT paused FROM pas.migration_sync_control WHERE sync_name = ? FOR SHARE",
            Boolean.class,
            SYNC_NAME);
    return !paused.isEmpty() && Boolean.TRUE.equals(paused.get(0));
  }

  public void setPaused(boolean paused, String reason, String by) {
    jdbc.update(
        "UPDATE pas.migration_sync_control SET paused = ?, paused_reason = ?,"
            + " updated_at = LOCALTIMESTAMP, updated_by = ? WHERE sync_name = ?",
        paused,
        reason,
        by,
        SYNC_NAME);
  }
}
