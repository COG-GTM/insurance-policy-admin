package com.acme.insurance.pas.batch.migration.report;

import com.acme.insurance.pas.batch.migration.SyncState;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code GET /actuator/migration}: latest reconciliation and parallel-run summary, consecutive
 * passing days, sync pause state and watermarks. Counts and paths only — never record values.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shares Spring-managed collaborators (JDBC, services) by design")
@Endpoint(id = "migration")
public class MigrationStatusEndpoint {

  private final CheckRunStore store;
  private final SyncState syncState;
  private final JdbcTemplate jdbc;

  public MigrationStatusEndpoint(CheckRunStore store, SyncState syncState, JdbcTemplate jdbc) {
    this.store = store;
    this.syncState = syncState;
    this.jdbc = jdbc;
  }

  @ReadOperation
  public Map<String, Object> status() {
    Map<String, Object> status = new LinkedHashMap<>();
    for (CheckType type : CheckType.values()) {
      Map<String, Object> check = new LinkedHashMap<>();
      check.put("latest", store.latest(type).orElse(null));
      check.put("consecutivePassingDays", store.consecutivePassingDays(type));
      status.put(type.name().toLowerCase(java.util.Locale.ROOT), check);
    }
    Map<String, Object> sync = new LinkedHashMap<>();
    sync.put("paused", syncState.paused());
    sync.put(
        "watermarks",
        jdbc.queryForList(
            "SELECT source_table, watermark_ts, rows_applied, last_run_at"
                + " FROM pas.migration_watermarks ORDER BY source_table"));
    status.put("sync", sync);
    return status;
  }
}
