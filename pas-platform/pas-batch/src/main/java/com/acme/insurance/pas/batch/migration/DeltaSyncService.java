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

  /** Lower bound used when a table has no watermark yet (e.g. it was empty at bulk load). */
  static final LocalDateTime NO_WATERMARK = LocalDateTime.of(1900, 1, 1, 0, 0);

  /** Raised inside a chunk transaction when the sync was paused mid-run; rolls the chunk back. */
  static final class PausedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PausedException() {
      super("DB2_TO_PAS sync paused");
    }
  }

  public Result sync(String runId) {
    if (state.paused()) {
      LOG.warn("delta sync {} skipped: DB2_TO_PAS sync is paused (rollback or maintenance)", runId);
      return new Result(runId, true, Map.of());
    }
    Map<String, Long> applied = new LinkedHashMap<>();
    try {
      LocalDateTime policiesSince = null;
      LocalDateTime[] policiesNewest = {null};
      long policyRows = 0;
      for (MigrationTable table : MigrationTable.values()) {
        if (table == MigrationTable.COVERAGES) {
          applied.put(table.sourceTable(), syncCoverages(policiesSince));
          // POLICIES advances only after its coverages are applied, so a failed coverage pass is
          // retried from the same lower bound.
          if (policiesNewest[0] != null) {
            state.saveWatermark(MigrationTable.POLICIES, policiesNewest[0], policyRows, runId);
          }
          continue;
        }
        LocalDateTime watermark =
            state
                .watermark(table)
                .orElseGet(
                    () -> {
                      LOG.warn(
                          "no watermark for {}; delta re-reads the whole table",
                          table.sourceTable());
                      return NO_WATERMARK;
                    });
        LocalDateTime since = watermark.minus(properties.deltaOverlap());
        LocalDateTime[] newest =
            table == MigrationTable.POLICIES ? policiesNewest : new LocalDateTime[1];
        long rows = syncTable(table, since, newest);
        if (table == MigrationTable.POLICIES) {
          policiesSince = since;
          policyRows = rows;
        } else if (newest[0] != null) {
          state.saveWatermark(table, newest[0], rows, runId);
        }
        applied.put(table.sourceTable(), rows);
      }
    } catch (PausedException e) {
      LOG.warn("delta sync {} stopped: paused mid-run after {}", runId, applied);
      return new Result(runId, true, applied);
    }
    LOG.info("delta sync {} applied {}", runId, applied);
    return new Result(runId, false, applied);
  }

  private long syncTable(MigrationTable table, LocalDateTime since, LocalDateTime[] newest) {
    String column = table.watermarkColumn().toUpperCase(Locale.ROOT);
    Object param =
        table.dateWatermark()
            ? Date.valueOf(since.toLocalDate())
            : (Object) Timestamp.valueOf(since);
    String sql =
        SourceRows.selectAll(source, table) + " WHERE " + column + " >= ? ORDER BY " + column;
    return stream(
        table,
        sql,
        param,
        row -> {
          LocalDateTime value = asDateTime(row.get(table.watermarkColumn()));
          if (value != null && (newest[0] == null || value.isAfter(newest[0]))) {
            newest[0] = value;
          }
        });
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
    Integer written =
        tx.execute(
            status -> {
              if (state.pausedLocked()) {
                throw new PausedException();
              }
              return writer.write(table, chunk);
            });
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
