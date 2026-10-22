package com.acme.insurance.pas.batch.migration.delta;

import com.acme.insurance.pas.batch.migration.MigrationProperties;
import com.acme.insurance.pas.batch.migration.bulk.MigrationRowWriter;
import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are retained for the lifetime of this service.")
public final class DeltaSyncService {
  private static final Logger log = LoggerFactory.getLogger(DeltaSyncService.class);
  private static final LocalDateTime INITIAL_WATERMARK = LocalDateTime.of(1900, 1, 1, 0, 0);
  private static final List<MigrationTable> WATERMARKED_TABLES =
      List.of(
          MigrationTable.POLICIES,
          MigrationTable.POLICY_HOLDERS,
          MigrationTable.ENDORSEMENTS,
          MigrationTable.UNDERWRITING_DECISIONS,
          MigrationTable.PREMIUMS);

  private final Db2Source source;
  private final MigrationRowWriter writer;
  private final JdbcTemplate jdbc;
  private final MigrationProperties properties;
  private final MeterRegistry metrics;
  private final ZoneId sourceZone;
  private final Map<String, AtomicLong> lagGauges = new ConcurrentHashMap<>();

  public DeltaSyncService(
      Db2Source source,
      MigrationRowWriter writer,
      JdbcTemplate jdbc,
      MigrationProperties properties,
      MeterRegistry metrics) {
    this.source = source;
    this.writer = writer;
    this.jdbc = jdbc;
    this.properties = properties;
    this.metrics = metrics;
    sourceZone = ZoneId.of(properties.source().zone());
  }

  public void sync() {
    LocalDateTime runStarted = source.currentTimestamp();
    OffsetDateTime syncedThrough = runStarted.atZone(sourceZone).toOffsetDateTime();
    Set<String> changedPolicies = new LinkedHashSet<>();
    Set<String> changedHolders = new LinkedHashSet<>();
    for (MigrationTable table : WATERMARKED_TABLES) {
      processTable(table, runStarted, syncedThrough, changedPolicies, changedHolders);
    }
    refreshPolicyDependents(changedPolicies);
    log.atInfo()
        .addKeyValue("event", "pas.migration.delta.completed")
        .addKeyValue("tables", WATERMARKED_TABLES.size())
        .addKeyValue("policiesChanged", changedPolicies.size())
        .addKeyValue("holdersChanged", changedHolders.size())
        .addKeyValue("syncedThrough", syncedThrough)
        .log("Forward delta sync completed");
  }

  private void processTable(
      MigrationTable table,
      LocalDateTime runStarted,
      OffsetDateTime syncedThrough,
      Set<String> changedPolicies,
      Set<String> changedHolders) {
    LocalDateTime previous = currentWatermark(table);
    LocalDateTime lowerBound = previous.minus(properties.deltaSync().overlap());
    LocalDateTime cursorWatermark =
        lowerBound.isBefore(INITIAL_WATERMARK) ? INITIAL_WATERMARK : lowerBound;
    List<Object> lastKey = List.of();
    LocalDateTime maximumSeen = previous;
    int applied = 0;
    while (true) {
      List<Map<String, Object>> rows =
          source.readChanges(
              table, cursorWatermark, runStarted, lastKey, properties.source().fetchSize());
      if (rows.isEmpty()) {
        break;
      }
      for (Map<String, Object> row : rows) {
        writer.upsert(table, row);
        LocalDateTime sourceValue =
            sourceTimestamp(table, row.get(table.changeColumn().toLowerCase()));
        if (sourceValue.isAfter(maximumSeen)) {
          maximumSeen = sourceValue;
        }
        if (table == MigrationTable.POLICIES) {
          changedPolicies.add((String) row.get("policy_number"));
          Object holder = row.get("policyholder_id");
          if (holder instanceof String custId) {
            changedHolders.add(custId);
          }
        } else if (table == MigrationTable.POLICY_HOLDERS) {
          changedHolders.add((String) row.get("cust_id"));
        }
        applied++;
      }
      Map<String, Object> last = rows.getLast();
      lastKey = table.keyColumns().stream().map(key -> last.get(key.toLowerCase())).toList();
    }
    LocalDateTime storedWatermark = maximumSeen.isAfter(previous) ? maximumSeen : previous;
    jdbc.update(
        """
        INSERT INTO pas_batch.sync_watermark
            (table_name, direction, watermark_value, synced_through, updated_at)
        VALUES (?, 'FORWARD', ?, ?, now())
        ON CONFLICT (table_name, direction) DO UPDATE
        SET watermark_value = EXCLUDED.watermark_value,
            synced_through = EXCLUDED.synced_through,
            updated_at = now()
        """,
        table.sourceName(),
        storedWatermark,
        syncedThrough);
    updateLag(table.sourceName(), Duration.between(storedWatermark, runStarted));
    metrics.counter("pas.migration.delta.rows", "table", table.targetName()).increment(applied);
    log.atInfo()
        .addKeyValue("event", "pas.migration.delta.table")
        .addKeyValue("table", table.sourceName())
        .addKeyValue("rowsApplied", applied)
        .addKeyValue("lagSeconds", Duration.between(storedWatermark, runStarted).toSeconds())
        .log("Forward delta table synchronized");
  }

  private void refreshPolicyDependents(Set<String> policyNumbers) {
    List<String> batch = new ArrayList<>(100);
    for (String policyNumber : policyNumbers) {
      batch.add(policyNumber);
      if (batch.size() == 100) {
        refreshPolicyBatch(batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      refreshPolicyBatch(batch);
    }
  }

  private void refreshPolicyBatch(List<String> policyNumbers) {
    List<Map<String, Object>> policies =
        source.readByLeadingKeys(MigrationTable.POLICIES, policyNumbers, policyNumbers.size());
    List<String> holderIds =
        policies.stream()
            .map(policy -> (String) policy.get("policyholder_id"))
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    for (String policyNumber : policyNumbers) {
      jdbc.update("DELETE FROM pas.coverages WHERE policy_number = ?", policyNumber);
    }
    List<Map<String, Object>> coverages =
        source.readByLeadingKeys(MigrationTable.COVERAGES, policyNumbers, 100_000);
    coverages.forEach(row -> writer.upsert(MigrationTable.COVERAGES, row));
    for (int index = 0; index < holderIds.size(); index += 100) {
      List<String> batch = holderIds.subList(index, Math.min(index + 100, holderIds.size()));
      source
          .readByLeadingKeys(MigrationTable.POLICY_HOLDERS, batch, batch.size())
          .forEach(row -> writer.upsert(MigrationTable.POLICY_HOLDERS, row));
    }
  }

  private LocalDateTime currentWatermark(MigrationTable table) {
    List<LocalDateTime> values =
        jdbc.query(
            """
            SELECT watermark_value
            FROM pas_batch.sync_watermark
            WHERE table_name = ? AND direction = 'FORWARD'
            """,
            (resultSet, rowNum) -> {
              java.sql.Timestamp value = resultSet.getTimestamp(1);
              return value == null ? INITIAL_WATERMARK : value.toLocalDateTime();
            },
            table.sourceName());
    return values.isEmpty() ? INITIAL_WATERMARK : values.getFirst();
  }

  private void updateLag(String table, Duration lag) {
    AtomicLong gauge =
        lagGauges.computeIfAbsent(
            table,
            name ->
                metrics.gauge("pas.sync.lag.seconds", Tags.of("table", name), new AtomicLong()));
    gauge.set(Math.max(0, lag.toSeconds()));
    if (lag.compareTo(properties.deltaSync().lagAlertThreshold()) > 0) {
      metrics.counter("pas.sync.lag.alerts", "table", table).increment();
      log.atWarn()
          .addKeyValue("event", "pas.sync.lag.alert")
          .addKeyValue("table", table)
          .addKeyValue("lagSeconds", lag.toSeconds())
          .addKeyValue("thresholdSeconds", properties.deltaSync().lagAlertThreshold().toSeconds())
          .log("Forward delta sync lag exceeds threshold");
    }
  }

  private static LocalDateTime sourceTimestamp(MigrationTable table, Object value) {
    return value instanceof LocalDate date ? date.atStartOfDay() : (LocalDateTime) value;
  }
}
