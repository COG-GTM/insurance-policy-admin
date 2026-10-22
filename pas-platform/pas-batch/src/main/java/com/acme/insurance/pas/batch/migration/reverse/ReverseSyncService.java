package com.acme.insurance.pas.batch.migration.reverse;

import com.acme.insurance.pas.batch.migration.MigrationProperties;
import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import com.acme.insurance.pas.batch.migration.source.ReverseSyncTarget;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiContext;
import com.acme.insurance.pas.pii.PolicyHolderPii;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are retained for the lifetime of this service.")
public final class ReverseSyncService {
  private static final Logger log = LoggerFactory.getLogger(ReverseSyncService.class);
  private static final int PAGE_SIZE = 250;
  private static final String[] POLICY_COLUMNS = {
    "POLICY_NUMBER",
    "POLICY_TYPE",
    "POLICY_STATUS",
    "EFFECTIVE_DATE",
    "EXPIRY_DATE",
    "POLICYHOLDER_ID",
    "AGENT_CODE",
    "BRANCH_CODE",
    "TOTAL_PREMIUM",
    "DEDUCTIBLE",
    "COVERAGE_LIMIT",
    "INCEPTION_DATE",
    "RENEWAL_COUNT",
    "UW_STATUS",
    "RISK_SCORE",
    "WEB_INDICATOR",
    "API_FLAG",
    "LAST_UPDATED",
    "UPDATED_BY"
  };
  private static final Map<MigrationTable, List<String>> CHILD_COLUMNS =
      Map.of(
          MigrationTable.COVERAGES,
          MigrationTable.COVERAGES.columns(),
          MigrationTable.PREMIUMS,
          MigrationTable.PREMIUMS.columns(),
          MigrationTable.ENDORSEMENTS,
          MigrationTable.ENDORSEMENTS.columns(),
          MigrationTable.UNDERWRITING_DECISIONS,
          MigrationTable.UNDERWRITING_DECISIONS.columns());

  private final JdbcTemplate jdbc;
  private final Db2Source source;
  private final ObjectProvider<ReverseSyncTarget> targetProvider;
  private final MigrationProperties properties;
  private final PiiCipher cipher;
  private final MeterRegistry metrics;
  private final ZoneId sourceZone;
  private final Map<String, AtomicLong> reverseLagGauges = new ConcurrentHashMap<>();

  public ReverseSyncService(
      JdbcTemplate jdbc,
      Db2Source source,
      ObjectProvider<ReverseSyncTarget> targetProvider,
      MigrationProperties properties,
      PiiCipher cipher,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.source = source;
    this.targetProvider = targetProvider;
    this.properties = properties;
    this.cipher = cipher;
    this.metrics = metrics;
    sourceZone = ZoneId.of(properties.source().zone());
  }

  public int drain() {
    if (!properties.reverseSync().enabled()) {
      return 0;
    }
    ReverseSyncTarget target = targetProvider.getObject();
    int totalApplied = 0;
    for (int pass = 1; pass <= properties.reverseSync().maxPasses(); pass++) {
      int passApplied = syncChangeLog(target);
      totalApplied += passApplied;
      metrics.counter("pas.migration.reverse.passes").increment();
      log.atInfo()
          .addKeyValue("event", "pas.migration.reverse.pass")
          .addKeyValue("pass", pass)
          .addKeyValue("rowsApplied", passApplied)
          .log("Reverse-sync drain pass completed");
      if (passApplied == 0) {
        updateReverseLag("policies", 0);
        updateReverseLag("policy_holders", 0);
        return totalApplied;
      }
    }
    throw new IllegalStateException(
        "Reverse-sync drain did not reach zero applied rows within the configured pass limit");
  }

  public int syncOnce() {
    if (!properties.reverseSync().enabled()) {
      return 0;
    }
    ReverseSyncTarget target = targetProvider.getObject();
    return syncChangeLog(target);
  }

  private int syncChangeLog(ReverseSyncTarget target) {
    String capturedXmin =
        jdbc.queryForObject("SELECT pg_snapshot_xmin(pg_current_snapshot())::text", String.class);
    ChangeWatermark watermark = reverseChangeWatermark();
    String previousXmin = watermark.reverseXmin() == null ? "0" : watermark.reverseXmin();
    long afterId = 0;
    LocalDateTime maximumChangedAt = watermark.changedAt();
    int changesApplied = 0;
    while (true) {
      List<Change> changes =
          jdbc.query(
              """
              SELECT id, table_name, record_key, policy_number, changed_at
              FROM pas.change_log
              WHERE tx_id >= ?::xid8 AND id > ?
              ORDER BY id
              FETCH FIRST ? ROWS ONLY
              """,
              (resultSet, rowNum) ->
                  new Change(
                      resultSet.getLong("id"),
                      resultSet.getString("table_name"),
                      resultSet.getString("record_key"),
                      resultSet.getString("policy_number"),
                      resultSet
                          .getObject("changed_at", OffsetDateTime.class)
                          .withOffsetSameInstant(ZoneOffset.UTC)
                          .toLocalDateTime()),
              previousXmin,
              afterId,
              PAGE_SIZE);
      if (changes.isEmpty()) {
        break;
      }
      Set<String> changedPolicies = new LinkedHashSet<>();
      Set<String> changedHolders = new LinkedHashSet<>();
      for (Change change : changes) {
        changesApplied++;
        if (change.changedAt().isAfter(maximumChangedAt)) {
          maximumChangedAt = change.changedAt();
        }
        if (change.tableName().equals("POLICY_HOLDERS")) {
          changedHolders.add(change.recordKey());
        } else if (change.policyNumber() != null) {
          changedPolicies.add(change.policyNumber());
        }
      }
      target.transaction(
          connection -> {
            for (String policyNumber : changedPolicies) {
              List<Map<String, Object>> policies =
                  jdbc.queryForList(
                      """
                      SELECT policy_number, policy_type, policy_status, effective_date, expiry_date,
                             policyholder_id, agent_code, branch_code, total_premium, deductible,
                             coverage_limit, inception_date, renewal_count, uw_status, risk_score,
                             web_indicator, api_flag, last_updated, updated_by
                      FROM pas.policies WHERE policy_number = ?
                      """,
                      policyNumber);
              if (policies.isEmpty()) {
                deletePolicy(connection, policyNumber);
              } else {
                syncPolicy(connection, policies.getFirst());
              }
            }
            for (String custId : changedHolders) {
              List<Map<String, Object>> holders =
                  jdbc.queryForList(
                      """
                      SELECT cust_id, cust_type, last_name, first_name, middle_init, company_name,
                             addr_line1, addr_line2, city, state_code, zip_code, country_code, phone,
                             email, date_of_birth_enc, ssn_last4_enc, tax_id_enc, credit_score_enc,
                             risk_tier, gdpr_consent, created_date, last_updated
                      FROM pas.policy_holders WHERE cust_id = ?
                      """,
                      custId);
              if (holders.isEmpty()) {
                deleteHolder(connection, custId);
              } else {
                merge(
                    connection,
                    "POLICY_HOLDERS",
                    MigrationTable.POLICY_HOLDERS.columns(),
                    "CUST_ID",
                    normalize(decryptHolder(holders.getFirst())));
              }
            }
          });
      afterId = changes.getLast().id();
    }
    jdbc.update(
        """
        INSERT INTO pas_batch.sync_watermark
            (table_name, direction, watermark_value, reverse_xmin, synced_through, updated_at)
        VALUES ('CHANGE_LOG', 'REVERSE', ?, ?::xid8, now(), now())
        ON CONFLICT (table_name, direction) DO UPDATE
        SET watermark_value = GREATEST(
                COALESCE(pas_batch.sync_watermark.watermark_value, TIMESTAMP '1900-01-01'),
                EXCLUDED.watermark_value),
            reverse_xmin = EXCLUDED.reverse_xmin,
            synced_through = EXCLUDED.synced_through,
            updated_at = now()
        """,
        maximumChangedAt,
        capturedXmin);
    metrics.counter("pas.migration.reverse.changes").increment(changesApplied);
    return changesApplied;
  }

  public int pruneChangeLog() {
    if (!properties.reverseSync().enabled()) {
      return 0;
    }
    String reverseXmin = reverseChangeWatermark().reverseXmin();
    if (reverseXmin == null) {
      return 0;
    }
    int totalPruned = 0;
    int pruned;
    do {
      pruned =
          jdbc.update(
              """
              DELETE FROM pas.change_log
              WHERE id IN (
                  SELECT id FROM pas.change_log
                  WHERE tx_id < ?::xid8
                  ORDER BY id
                  LIMIT ?
              )
              """,
              reverseXmin,
              PAGE_SIZE);
      totalPruned += pruned;
    } while (pruned == PAGE_SIZE);
    metrics.counter("pas.sync.reverse.change_log.pruned").increment(totalPruned);
    return totalPruned;
  }

  private ChangeWatermark reverseChangeWatermark() {
    List<ChangeWatermark> watermarks =
        jdbc.query(
            """
            SELECT reverse_xmin::text AS reverse_xmin, watermark_value
            FROM pas_batch.sync_watermark
            WHERE table_name = 'CHANGE_LOG' AND direction = 'REVERSE'
            """,
            (resultSet, rowNum) -> {
              LocalDateTime timestamp = resultSet.getObject("watermark_value", LocalDateTime.class);
              return new ChangeWatermark(
                  resultSet.getString("reverse_xmin"),
                  timestamp == null ? LocalDateTime.of(1900, 1, 1, 0, 0) : timestamp);
            });
    return watermarks.isEmpty()
        ? new ChangeWatermark(null, LocalDateTime.of(1900, 1, 1, 0, 0))
        : watermarks.getFirst();
  }

  private void deletePolicy(Connection connection, String policyNumber) {
    for (MigrationTable table :
        List.of(
            MigrationTable.COVERAGES,
            MigrationTable.PREMIUMS,
            MigrationTable.ENDORSEMENTS,
            MigrationTable.UNDERWRITING_DECISIONS)) {
      deleteByPolicy(connection, table.sourceName(), policyNumber);
    }
    deleteByPolicy(connection, "POLICIES", policyNumber);
  }

  private void deleteHolder(Connection connection, String custId) {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "DELETE FROM " + source.schema() + ".POLICY_HOLDERS WHERE CUST_ID = ?")) {
      statement.setObject(1, custId, Types.CHAR);
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to delete reverse-sync holder row", exception);
    }
  }

  private void syncPolicy(Connection connection, Map<String, Object> policy) {
    Map<String, Object> normalized = normalize(policy);
    merge(connection, "POLICIES", List.of(POLICY_COLUMNS), "POLICY_NUMBER", normalized);
    String policyNumber = (String) policy.get("policy_number");
    List<Map<String, Object>> coverages =
        jdbc.queryForList(
            "SELECT policy_number, sequence_num, coverage_type, description, coverage_limit, deductible, premium, effective_date, expiry_date, status, coinsurance_pct, rating_territory, class_code "
                + "FROM pas.coverages WHERE policy_number = ? ORDER BY sequence_num",
            policyNumber);
    deleteByPolicy(connection, "COVERAGES", policyNumber);
    mergeRows(connection, MigrationTable.COVERAGES, coverages);
    for (MigrationTable table :
        List.of(
            MigrationTable.PREMIUMS,
            MigrationTable.ENDORSEMENTS,
            MigrationTable.UNDERWRITING_DECISIONS)) {
      deleteByPolicy(connection, table.sourceName(), policyNumber);
      mergeRows(connection, table, childRows(table, policyNumber));
    }
  }

  private List<Map<String, Object>> childRows(MigrationTable table, String policyNumber) {
    String columns =
        table.columns().stream()
            .map(column -> column.toLowerCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.joining(", "));
    String sql =
        "SELECT "
            + columns
            + " FROM pas."
            + table.targetName()
            + " WHERE policy_number = ? ORDER BY "
            + table.keyColumns().stream()
                .map(column -> column.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "));
    if (table == MigrationTable.UNDERWRITING_DECISIONS) {
      sql =
          "SELECT "
              + columns
              + " FROM (SELECT "
              + columns
              + ", row_number() OVER (PARTITION BY policy_number, decision_date "
              + "ORDER BY decision_id DESC) AS source_rank FROM pas.underwriting_decisions "
              + "WHERE policy_number = ?) decisions WHERE source_rank = 1 "
              + "ORDER BY policy_number, decision_date";
    }
    return jdbc.queryForList(sql, policyNumber);
  }

  private void mergeRows(
      Connection connection, MigrationTable table, List<Map<String, Object>> rows) {
    List<String> columns = CHILD_COLUMNS.get(table).stream().map(String::toUpperCase).toList();
    for (Map<String, Object> row : rows) {
      merge(connection, table.sourceName(), columns, table.keyColumns(), normalize(row));
    }
  }

  private void updateReverseLag(String table, long lagSeconds) {
    AtomicLong gauge =
        reverseLagGauges.computeIfAbsent(
            table,
            name ->
                metrics.gauge(
                    "pas.sync.reverse.lag.seconds", Tags.of("table", name), new AtomicLong()));
    gauge.set(Math.max(0, lagSeconds));
  }

  private Map<String, Object> decryptHolder(Map<String, Object> holder) {
    String custId = ((String) holder.get("cust_id")).stripTrailing();
    Map<String, Object> result = new LinkedHashMap<>(holder);
    result.put(
        "date_of_birth",
        PolicyHolderPii.decryptDate(
            (byte[]) holder.get("date_of_birth_enc"),
            cipher,
            new PiiContext("policy_holders", "date_of_birth_enc", custId)));
    result.put(
        "ssn_last4",
        PolicyHolderPii.decryptString(
            (byte[]) holder.get("ssn_last4_enc"),
            cipher,
            new PiiContext("policy_holders", "ssn_last4_enc", custId)));
    result.put(
        "tax_id",
        PolicyHolderPii.decryptString(
            (byte[]) holder.get("tax_id_enc"),
            cipher,
            new PiiContext("policy_holders", "tax_id_enc", custId)));
    result.put(
        "credit_score",
        PolicyHolderPii.decryptInteger(
            (byte[]) holder.get("credit_score_enc"),
            cipher,
            new PiiContext("policy_holders", "credit_score_enc", custId)));
    return result;
  }

  private void merge(
      Connection connection,
      String table,
      List<String> columns,
      String key,
      Map<String, Object> row) {
    merge(connection, table, columns, List.of(key), row);
  }

  @SuppressFBWarnings(
      value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      justification =
          "Schema, tables, and columns are fixed configuration or enum identifiers; values are bound.")
  private void merge(
      Connection connection,
      String table,
      List<String> columns,
      List<String> keys,
      Map<String, Object> row) {
    String columnSql = String.join(", ", columns);
    String placeholders = "?,".repeat(columns.size());
    String sourceAlias =
        "src ("
            + columns.stream()
                .map(column -> column.toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "))
            + ")";
    String on =
        keys.stream()
            .map(
                key ->
                    "target."
                        + key.toUpperCase(java.util.Locale.ROOT)
                        + " = src."
                        + key.toUpperCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.joining(" AND "));
    List<String> updateColumns =
        columns.stream()
            .filter(column -> keys.stream().noneMatch(key -> key.equalsIgnoreCase(column)))
            .toList();
    String sql =
        "MERGE INTO "
            + source.schema()
            + "."
            + table
            + " AS target USING (VALUES ("
            + placeholders.substring(0, placeholders.length() - 1)
            + ")) AS "
            + sourceAlias
            + " ON "
            + on
            + (updateColumns.isEmpty()
                ? ""
                : " WHEN MATCHED THEN UPDATE SET "
                    + updateColumns.stream()
                        .map(
                            column ->
                                column.toUpperCase(java.util.Locale.ROOT)
                                    + " = src."
                                    + column.toUpperCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.joining(", ")))
            + " WHEN NOT MATCHED THEN INSERT ("
            + columnSql
            + ") VALUES ("
            + columns.stream()
                .map(column -> "src." + column.toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "))
            + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < columns.size(); index++) {
        String column = columns.get(index).toLowerCase(java.util.Locale.ROOT);
        Object value = row.get(column);
        statement.setObject(index + 1, value, sqlType(column, value));
      }
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to merge reverse-sync row into " + table, exception);
    }
  }

  private void deleteByPolicy(Connection connection, String table, String policyNumber) {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "DELETE FROM " + source.schema() + "." + table + " WHERE POLICY_NUMBER = ?")) {
      statement.setObject(1, policyNumber, Types.CHAR);
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to replace DB2 coverage set", exception);
    }
  }

  private Map<String, Object> normalize(Map<String, Object> row) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    row.forEach(
        (key, value) -> {
          Object converted = value;
          if (value instanceof OffsetDateTime timestamp) {
            converted = timestamp.atZoneSameInstant(sourceZone).toLocalDateTime();
          } else if (value instanceof java.sql.Timestamp timestamp) {
            converted = timestamp.toInstant().atZone(sourceZone).toLocalDateTime();
          } else if (value instanceof java.sql.Date date) {
            converted = date.toLocalDate();
          }
          normalized.put(key.toLowerCase(java.util.Locale.ROOT), converted);
        });
    return normalized;
  }

  private static int sqlType(String column, Object value) {
    if (value instanceof LocalDate || value instanceof java.sql.Date) {
      return Types.DATE;
    }
    if (value instanceof LocalDateTime || value instanceof java.sql.Timestamp) {
      return Types.TIMESTAMP;
    }
    if (value instanceof Number) {
      return value instanceof Short ? Types.SMALLINT : Types.NUMERIC;
    }
    if (value instanceof Boolean) {
      return Types.BOOLEAN;
    }
    return switch (column) {
      case "effective_date",
              "expiry_date",
              "inception_date",
              "term_effective_date",
              "term_expiry_date",
              "processed_date",
              "decision_date",
              "date_of_birth",
              "created_date" ->
          Types.DATE;
      case "last_updated", "created_timestamp" -> Types.TIMESTAMP;
      default -> Types.CHAR;
    };
  }

  private record Change(
      long id, String tableName, String recordKey, String policyNumber, LocalDateTime changedAt) {}

  private record ChangeWatermark(String reverseXmin, LocalDateTime changedAt) {}
}
