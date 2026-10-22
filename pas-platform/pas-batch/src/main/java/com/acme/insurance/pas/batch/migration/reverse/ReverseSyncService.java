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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
      int passApplied = syncPolicyChanges(target) + syncHolderChanges(target);
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
    return syncPolicyChanges(target) + syncHolderChanges(target);
  }

  private int syncPolicyChanges(ReverseSyncTarget target) {
    int applied = 0;
    String afterPolicy = "";
    while (true) {
      List<Map<String, Object>> policies = changedPolicies(afterPolicy);
      if (policies.isEmpty()) {
        updateReverseLag("policies", 0);
        return applied;
      }
      target.transaction(
          connection -> {
            for (Map<String, Object> policy : policies) {
              syncPolicy(connection, policy);
            }
          });
      for (Map<String, Object> policy : policies) {
        long rowVersion = ((Number) policy.get("row_version")).longValue();
        String policyNumber = (String) policy.get("policy_number");
        jdbc.update(
            """
            INSERT INTO pas_batch.reverse_sync_policy_version (policy_number, row_version, synced_at)
            VALUES (?, ?, now())
            ON CONFLICT (policy_number) DO UPDATE
            SET row_version = EXCLUDED.row_version, synced_at = now()
            """,
            policyNumber,
            rowVersion);
        afterPolicy = policyNumber;
      }
      applied += policies.size();
      metrics.counter("pas.migration.reverse.rows", "table", "policies").increment(policies.size());
    }
  }

  private List<Map<String, Object>> changedPolicies(String afterPolicy) {
    return jdbc.queryForList(
        """
        SELECT p.policy_number, p.policy_type, p.policy_status, p.effective_date, p.expiry_date,
               p.policyholder_id, p.agent_code, p.branch_code, p.total_premium, p.deductible,
               p.coverage_limit, p.inception_date, p.renewal_count, p.uw_status, p.risk_score,
               p.web_indicator, p.api_flag, p.last_updated, p.updated_by, p.row_version
        FROM pas.policies p
        LEFT JOIN pas_batch.reverse_sync_policy_version v
          ON v.policy_number = p.policy_number
        WHERE p.row_version > COALESCE(v.row_version, 0)
          AND p.policy_number > ?
        ORDER BY p.policy_number
        FETCH FIRST ? ROWS ONLY
        """,
        afterPolicy,
        PAGE_SIZE);
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

  private int syncHolderChanges(ReverseSyncTarget target) {
    LocalDateTime sourceNow = source.currentTimestamp();
    LocalDateTime watermark = reverseWatermark();
    String afterCustId = "";
    int applied = 0;
    while (true) {
      List<Map<String, Object>> holders =
          jdbc.queryForList(
              """
              SELECT cust_id, cust_type, last_name, first_name, middle_init, company_name,
                     addr_line1, addr_line2, city, state_code, zip_code, country_code, phone,
                     email, date_of_birth_enc, ssn_last4_enc, tax_id_enc, credit_score_enc,
                     risk_tier, gdpr_consent, created_date, last_updated
              FROM pas.policy_holders
              WHERE last_updated > ? AND last_updated <= ? AND cust_id > ?
              ORDER BY cust_id
              FETCH FIRST ? ROWS ONLY
              """,
              watermark.atZone(sourceZone).toOffsetDateTime(),
              sourceNow.atZone(sourceZone).toOffsetDateTime(),
              afterCustId,
              PAGE_SIZE);
      if (holders.isEmpty()) {
        break;
      }
      List<Map<String, Object>> plaintextHolders =
          holders.stream().map(this::decryptHolder).toList();
      target.transaction(
          connection -> {
            for (Map<String, Object> holder : plaintextHolders) {
              merge(
                  connection,
                  "POLICY_HOLDERS",
                  MigrationTable.POLICY_HOLDERS.columns(),
                  "CUST_ID",
                  normalize(holder));
            }
          });
      for (Map<String, Object> holder : holders) {
        afterCustId = (String) holder.get("cust_id");
      }
      applied += holders.size();
    }
    if (applied > 0) {
      LocalDateTime maxUpdated =
          jdbc.queryForObject(
              """
              SELECT MAX(last_updated) AT TIME ZONE ?
              FROM pas.policy_holders
              WHERE last_updated > ? AND last_updated <= ?
              """,
              (resultSet, rowNum) -> {
                java.sql.Timestamp timestamp = resultSet.getTimestamp(1);
                return timestamp == null ? watermark : timestamp.toLocalDateTime();
              },
              sourceZone.getId(),
              watermark.atZone(sourceZone).toOffsetDateTime(),
              sourceNow.atZone(sourceZone).toOffsetDateTime());
      jdbc.update(
          """
          INSERT INTO pas_batch.sync_watermark
              (table_name, direction, watermark_value, synced_through, updated_at)
          VALUES ('POLICY_HOLDERS', 'REVERSE', ?, ?, now())
          ON CONFLICT (table_name, direction) DO UPDATE
          SET watermark_value = EXCLUDED.watermark_value,
              synced_through = EXCLUDED.synced_through,
              updated_at = now()
          """,
          maxUpdated,
          sourceNow.atZone(sourceZone).toOffsetDateTime());
    } else {
      jdbc.update(
          """
          INSERT INTO pas_batch.sync_watermark
              (table_name, direction, watermark_value, synced_through, updated_at)
          VALUES ('POLICY_HOLDERS', 'REVERSE', ?, ?, now())
          ON CONFLICT (table_name, direction) DO UPDATE
          SET synced_through = EXCLUDED.synced_through, updated_at = now()
          """,
          watermark,
          sourceNow.atZone(sourceZone).toOffsetDateTime());
    }
    metrics.counter("pas.migration.reverse.rows", "table", "policy_holders").increment(applied);
    updateReverseLag("policy_holders", 0);
    return applied;
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

  private LocalDateTime reverseWatermark() {
    List<LocalDateTime> values =
        jdbc.query(
            """
            SELECT watermark_value FROM pas_batch.sync_watermark
            WHERE table_name = 'POLICY_HOLDERS' AND direction = 'REVERSE'
            """,
            (resultSet, rowNum) -> {
              java.sql.Timestamp timestamp = resultSet.getTimestamp(1);
              return timestamp == null
                  ? LocalDateTime.of(1900, 1, 1, 0, 0)
                  : timestamp.toLocalDateTime();
            });
    return values.isEmpty() ? LocalDateTime.of(1900, 1, 1, 0, 0) : values.getFirst();
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
            + properties.source().schema()
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
            "DELETE FROM "
                + properties.source().schema()
                + "."
                + table
                + " WHERE POLICY_NUMBER = ?")) {
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
}
