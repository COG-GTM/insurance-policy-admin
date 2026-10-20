package com.acme.insurance.pas.batch.migration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled timestamp delta sync DB2 → {@code pas.*} (the CDC fallback in ADR-0001 D8). Each run
 * re-reads rows whose change column is at or after {@code watermark − overlap}, upserts them in
 * chunks and advances the watermark to the newest value applied.
 *
 * <p>Limits (see {@code docs/modernization/data-migration.md}): DB2 deletes are invisible (caught
 * by reconciliation as {@code EXTRA_IN_TARGET}); COVERAGES has no change column, so coverages are
 * re-synced for every policy whose LAST_UPDATED moved; PREMIUMS.CALC_DATE is a DATE, so the whole
 * watermark day is re-read. Production should use log-based CDC (IBM IIDR or Debezium Db2).
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shares Spring-managed collaborators (JDBC, services) by design")
public final class DeltaSyncService {

  private static final Logger LOG = LoggerFactory.getLogger(DeltaSyncService.class);

  /** Outcome of one delta run. */
  public record Result(String runId, boolean paused, Map<String, Long> rowsApplied) {
    public Result {
      rowsApplied = Map.copyOf(rowsApplied);
    }
  }

  private final SourceDatabase source;
  private final TargetWriter writer;
  private final SyncState state;
  private final TransactionTemplate tx;
  private final MigrationProperties properties;

  public DeltaSyncService(
      SourceDatabase source,
      TargetWriter writer,
      SyncState state,
      TransactionTemplate tx,
      MigrationProperties properties) {
    this.source = source;
    this.writer = writer;
    this.state = state;
    this.tx = tx;
    this.properties = properties;
  }

  public Result sync(String runId) {
    if (state.paused()) {
      LOG.warn("delta sync {} skipped: DB2_TO_PAS sync is paused (rollback or maintenance)", runId);
      return new Result(runId, true, Map.of());
    }
    Map<String, Long> applied = new LinkedHashMap<>();
    LocalDateTime policiesSince = null;
    for (MigrationTable table : MigrationTable.values()) {
      if (table == MigrationTable.COVERAGES) {
        applied.put(table.sourceTable(), syncCoverages(policiesSince));
        continue;
      }
      LocalDateTime watermark =
          state
              .watermark(table)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "no watermark for "
                              + table.sourceTable()
                              + "; run "
                              + BulkLoadJobConfig.JOB_NAME
                              + " first"));
      LocalDateTime since = watermark.minus(properties.deltaOverlap());
      if (table == MigrationTable.POLICIES) {
        policiesSince = since;
      }
      long rows = syncTable(table, since, runId);
      applied.put(table.sourceTable(), rows);
    }
    LOG.info("delta sync {} applied {}", runId, applied);
    return new Result(runId, false, applied);
  }

  private long syncTable(MigrationTable table, LocalDateTime since, String runId) {
    String column = table.watermarkColumn().toUpperCase(Locale.ROOT);
    Object param =
        table.dateWatermark()
            ? Date.valueOf(since.toLocalDate())
            : (Object) Timestamp.valueOf(since);
    String sql =
        SourceRows.selectAll(source, table) + " WHERE " + column + " >= ? ORDER BY " + column;
    LocalDateTime[] newest = {null};
    long rows =
        stream(
            table,
            sql,
            param,
            row -> {
              LocalDateTime value = asDateTime(row.get(table.watermarkColumn()));
              if (value != null && (newest[0] == null || value.isAfter(newest[0]))) {
                newest[0] = value;
              }
            });
    if (newest[0] != null) {
      state.saveWatermark(table, newest[0], rows, runId);
    }
    return rows;
  }

  private long syncCoverages(LocalDateTime policiesSince) {
    if (policiesSince == null) {
      return 0;
    }
    String sql =
        SourceRows.selectAll(source, MigrationTable.COVERAGES)
            + " WHERE POLICY_NUMBER IN (SELECT POLICY_NUMBER FROM "
            + source.table(MigrationTable.POLICIES.sourceTable())
            + " WHERE LAST_UPDATED >= ?)";
    return stream(MigrationTable.COVERAGES, sql, Timestamp.valueOf(policiesSince), row -> {});
  }

  private long stream(
      MigrationTable table,
      String sql,
      Object param,
      java.util.function.Consumer<Map<String, Object>> observer) {
    List<Map<String, Object>> buffer = new ArrayList<>(properties.chunkSize());
    long[] total = {0};
    source
        .jdbc()
        .query(
            sql,
            rs -> {
              Map<String, Object> row = SourceRows.INSTANCE.mapRow(rs, 0);
              observer.accept(row);
              buffer.add(row);
              if (buffer.size() >= properties.chunkSize()) {
                total[0] += flush(table, buffer);
              }
            },
            param);
    total[0] += flush(table, buffer);
    return total[0];
  }

  private int flush(MigrationTable table, List<Map<String, Object>> buffer) {
    if (buffer.isEmpty()) {
      return 0;
    }
    List<Map<String, Object>> chunk = List.copyOf(buffer);
    buffer.clear();
    Integer written = tx.execute(status -> writer.write(table, chunk));
    return written == null ? 0 : written;
  }

  private static LocalDateTime asDateTime(Object value) {
    if (value instanceof Timestamp ts) {
      return ts.toLocalDateTime();
    }
    if (value instanceof Date date) {
      return date.toLocalDate().atStartOfDay();
    }
    if (value instanceof LocalDateTime ldt) {
      return ldt;
    }
    if (value instanceof LocalDate ld) {
      return ld.atStartOfDay();
    }
    return null;
  }
}
