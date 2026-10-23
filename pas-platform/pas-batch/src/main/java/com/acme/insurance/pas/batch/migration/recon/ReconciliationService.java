package com.acme.insurance.pas.batch.migration.recon;

import com.acme.insurance.pas.batch.migration.MigrationProperties;
import com.acme.insurance.pas.batch.migration.pii.PiiProperties;
import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiContext;
import com.acme.insurance.pas.pii.PolicyHolderPii;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
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
public final class ReconciliationService {
  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private static final int PAGE_SIZE = 500;
  private static final List<MigrationTable> HASH_TABLES =
      List.of(
          MigrationTable.POLICIES,
          MigrationTable.COVERAGES,
          MigrationTable.PREMIUMS,
          MigrationTable.POLICY_HOLDERS);
  private final Db2Source source;
  private final JdbcTemplate jdbc;
  private final MigrationProperties properties;
  private final PiiCipher cipher;
  private final MeterRegistry metrics;
  private final ObjectMapper objectMapper;
  private final ZoneId sourceZone;
  private final String fingerprintKeyConfiguration;
  private final PiiProperties piiProperties;
  private volatile SecretKey fingerprintKey;
  private final Map<String, AtomicLong> differenceGauges = new ConcurrentHashMap<>();
  private final AtomicLong lastRunTimestamp = new AtomicLong();

  public ReconciliationService(
      Db2Source source,
      JdbcTemplate jdbc,
      MigrationProperties properties,
      PiiProperties piiProperties,
      PiiCipher cipher,
      MeterRegistry metrics,
      ObjectMapper objectMapper) {
    this.source = source;
    this.jdbc = jdbc;
    this.properties = properties;
    this.cipher = cipher;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
    sourceZone = ZoneId.of(properties.source().zone());
    fingerprintKeyConfiguration = properties.recon().fingerprintKey();
    this.piiProperties = piiProperties;
    metrics.gauge("pas.recon.last.run.timestamp", lastRunTimestamp);
  }

  public Result reconcile() {
    fingerprintKey();
    OffsetDateTime startedAt = OffsetDateTime.now();
    Long runId =
        jdbc.queryForObject(
            """
            INSERT INTO pas_batch.recon_run (started_at, status, counts)
            VALUES (?, 'FAILED', '{}'::jsonb)
            RETURNING id
            """,
            Long.class,
            startedAt);
    long reconciliationRunId =
        java.util.Objects.requireNonNull(runId, "Reconciliation run was not created");
    List<Difference> differences = new ArrayList<>();
    Map<String, Object> counts = new LinkedHashMap<>();
    try {
      for (MigrationTable table : MigrationTable.bulkLoadOrder()) {
        TableCounts count = reconcileTable(reconciliationRunId, table, differences);
        counts.put(table.targetName(), Map.of("source", count.source(), "target", count.target()));
      }
      compareTotals(reconciliationRunId, differences);
      String status = differences.isEmpty() ? "CLEAN" : "DIFFERENCES";
      persistDifferences(reconciliationRunId, differences);
      jdbc.update(
          """
          UPDATE pas_batch.recon_run
          SET finished_at = now(), status = ?, counts = ?::jsonb
          WHERE id = ?
          """,
          status,
          objectMapper.writeValueAsString(counts),
          reconciliationRunId);
      writeReports(reconciliationRunId, startedAt, status, counts, differences);
      updateMetrics(differences, startedAt);
      log.atInfo()
          .addKeyValue("event", "pas.migration.recon.completed")
          .addKeyValue("runId", reconciliationRunId)
          .addKeyValue("status", status)
          .addKeyValue("differenceCount", differences.size())
          .log("Migration reconciliation completed");
      return new Result(reconciliationRunId, status, differences.size(), counts);
    } catch (Exception exception) {
      jdbc.update(
          "UPDATE pas_batch.recon_run SET finished_at = now(), status = 'FAILED' WHERE id = ?",
          reconciliationRunId);
      throw exception instanceof RuntimeException runtime
          ? runtime
          : new IllegalStateException("Unable to complete reconciliation", exception);
    }
  }

  private TableCounts reconcileTable(
      long runId, MigrationTable table, List<Difference> differences) {
    Set<String> seenSource = new HashSet<>();
    Set<String> seenTarget = new HashSet<>();
    Set<String> seenCompared = new HashSet<>();
    long sourceCount = 0;
    long targetCount = 0;
    for (Db2Source.KeyRange range : ranges(table)) {
      Map<String, Map<String, Object>> sourceRows = readSourceRange(table, range);
      Map<String, Map<String, Object>> targetRows = readTargetRange(table, range);
      for (String key : sourceRows.keySet()) {
        if (seenSource.add(key)) {
          sourceCount++;
        }
      }
      for (String key : targetRows.keySet()) {
        if (seenTarget.add(key)) {
          targetCount++;
        }
      }
      Set<String> keys = new LinkedHashSet<>(sourceRows.keySet());
      keys.addAll(targetRows.keySet());
      for (String key : keys) {
        if (!seenCompared.add(key)) {
          continue;
        }
        Map<String, Object> sourceRow = sourceRows.get(key);
        Map<String, Object> targetRow = targetRows.get(key);
        if (sourceRow == null) {
          differences.add(
              new Difference(
                  runId,
                  table.targetName(),
                  "MISSING_IN_SOURCE",
                  externalRecordKey(table, key),
                  null,
                  null,
                  rowFingerprint(table, targetRow),
                  false));
        } else if (targetRow == null) {
          differences.add(
              new Difference(
                  runId,
                  table.targetName(),
                  "MISSING_IN_TARGET",
                  externalRecordKey(table, key),
                  null,
                  rowFingerprint(table, sourceRow),
                  null,
                  false));
        } else if (HASH_TABLES.contains(table)) {
          String sourceHash = rowFingerprint(table, sourceRow);
          String targetHash = rowFingerprint(table, targetRow);
          if (!sourceHash.equals(targetHash)) {
            differences.add(
                new Difference(
                    runId,
                    table.targetName(),
                    "HASH",
                    externalRecordKey(table, key),
                    null,
                    sourceHash,
                    targetHash,
                    false));
          }
        }
      }
    }
    if (sourceCount != targetCount) {
      differences.add(
          new Difference(
              runId,
              table.targetName(),
              "COUNT",
              null,
              null,
              Long.toString(sourceCount),
              Long.toString(targetCount),
              false));
    }
    return new TableCounts(sourceCount, targetCount);
  }

  private List<Db2Source.KeyRange> ranges(MigrationTable table) {
    List<Db2Source.KeyRange> ranges = new ArrayList<>(source.keyRanges(table, 8));
    ranges.addAll(targetKeyRanges(table));
    ranges.sort(Comparator.comparing(Db2Source.KeyRange::minimum));
    if (ranges.isEmpty()) {
      return List.of();
    }
    return ranges;
  }

  private List<Db2Source.KeyRange> targetKeyRanges(MigrationTable table) {
    StringBuilder filter = new StringBuilder();
    if (table == MigrationTable.UNDERWRITING_DECISIONS) {
      filter.append(" WHERE source_system = 'DB2'");
    } else if (table == MigrationTable.TERRITORY_FACTORS) {
      filter.append(" WHERE factor_set = 'DB2'");
    }
    String sql =
        "SELECT MIN(source_key), MAX(source_key) FROM ("
            + "SELECT source_key, NTILE(?) OVER (ORDER BY source_key) AS partition_no "
            + "FROM (SELECT DISTINCT "
            + table.leadingKey().toLowerCase(Locale.ROOT)
            + " AS source_key FROM pas."
            + table.targetName()
            + filter
            + ") distinct_keys) sampled "
            + "GROUP BY partition_no ORDER BY partition_no FETCH FIRST ? ROWS ONLY";
    return jdbc.query(
        sql,
        (resultSet, rowNum) ->
            new Db2Source.KeyRange(
                trimChar(resultSet.getString(1)), trimChar(resultSet.getString(2))),
        8,
        8);
  }

  private Map<String, Map<String, Object>> readSourceRange(
      MigrationTable table, Db2Source.KeyRange range) {
    Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
    List<Object> cursor = List.of();
    while (true) {
      List<Map<String, Object>> page =
          source.readPage(table, cursor, range.minimum(), range.maximum(), PAGE_SIZE);
      if (page.isEmpty()) {
        return rows;
      }
      for (Map<String, Object> row : page) {
        rows.put(recordKey(table, row), row);
      }
      Map<String, Object> last = page.getLast();
      cursor =
          table.keyColumns().stream().map(key -> last.get(key.toLowerCase(Locale.ROOT))).toList();
    }
  }

  private Map<String, Map<String, Object>> readTargetRange(
      MigrationTable table, Db2Source.KeyRange range) {
    Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
    List<Object> cursor = List.of();
    while (true) {
      List<Map<String, Object>> page = targetPage(table, range, cursor);
      if (page.isEmpty()) {
        return rows;
      }
      for (Map<String, Object> row : page) {
        Map<String, Object> comparable =
            table == MigrationTable.POLICY_HOLDERS ? decryptHolder(row) : row;
        rows.put(recordKey(table, comparable), comparable);
      }
      Map<String, Object> last = page.getLast();
      cursor =
          table.keyColumns().stream().map(key -> last.get(key.toLowerCase(Locale.ROOT))).toList();
    }
  }

  private List<Map<String, Object>> targetPage(
      MigrationTable table, Db2Source.KeyRange range, List<Object> afterKey) {
    String columns = targetColumns(table);
    List<String> predicates = new ArrayList<>();
    List<Object> parameters = new ArrayList<>();
    predicates.add(table.leadingKey().toLowerCase(Locale.ROOT) + " >= ?");
    parameters.add(range.minimum());
    predicates.add(table.leadingKey().toLowerCase(Locale.ROOT) + " <= ?");
    parameters.add(range.maximum());
    if (table == MigrationTable.UNDERWRITING_DECISIONS) {
      predicates.add("source_system = 'DB2'");
    } else if (table == MigrationTable.TERRITORY_FACTORS) {
      predicates.add("factor_set = 'DB2'");
    }
    appendKeyset(table, afterKey, predicates, parameters);
    String sql =
        "SELECT "
            + columns
            + " FROM pas."
            + table.targetName()
            + " WHERE "
            + String.join(" AND ", predicates)
            + " ORDER BY "
            + table.keyColumns().stream()
                .map(column -> column.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "))
            + " FETCH FIRST ? ROWS ONLY";
    parameters.add(PAGE_SIZE);
    return jdbc.query(
        sql,
        resultSet -> {
          List<Map<String, Object>> result = new ArrayList<>();
          var metadata = resultSet.getMetaData();
          while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
              Object value = resultSet.getObject(index);
              if (value instanceof java.sql.Date date) {
                value = date.toLocalDate();
              }
              row.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), value);
            }
            result.add(row);
          }
          return result;
        },
        parameters.toArray());
  }

  private String targetColumns(MigrationTable table) {
    if (table == MigrationTable.POLICY_HOLDERS) {
      return """
          cust_id, cust_type, last_name, first_name, middle_init, company_name, addr_line1,
          addr_line2, city, state_code, zip_code, country_code, phone, email, date_of_birth_enc,
          ssn_last4_enc, tax_id_enc, credit_score_enc, risk_tier, gdpr_consent, created_date,
          last_updated
          """
          .replace("\n", " ");
    }
    return table.columns().stream()
        .map(column -> column.toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private void appendKeyset(
      MigrationTable table,
      List<Object> afterKey,
      List<String> predicates,
      List<Object> parameters) {
    if (afterKey.isEmpty()) {
      return;
    }
    List<String> alternatives = new ArrayList<>();
    for (int index = 0; index < afterKey.size(); index++) {
      List<String> parts = new ArrayList<>();
      for (int prior = 0; prior < index; prior++) {
        parts.add(table.keyColumns().get(prior).toLowerCase(Locale.ROOT) + " = ?");
        parameters.add(afterKey.get(prior));
      }
      parts.add(table.keyColumns().get(index).toLowerCase(Locale.ROOT) + " > ?");
      parameters.add(afterKey.get(index));
      alternatives.add("(" + String.join(" AND ", parts) + ")");
    }
    predicates.add("(" + String.join(" OR ", alternatives) + ")");
  }

  private Map<String, Object> decryptHolder(Map<String, Object> row) {
    String custId = trimChar((String) row.get("cust_id"));
    Map<String, Object> result = new LinkedHashMap<>(row);
    result.put(
        "date_of_birth",
        PolicyHolderPii.decryptDate(
            (byte[]) row.get("date_of_birth_enc"),
            cipher,
            new PiiContext("policy_holders", "date_of_birth_enc", custId)));
    result.put(
        "ssn_last4",
        PolicyHolderPii.decryptString(
            (byte[]) row.get("ssn_last4_enc"),
            cipher,
            new PiiContext("policy_holders", "ssn_last4_enc", custId)));
    result.put(
        "tax_id",
        PolicyHolderPii.decryptString(
            (byte[]) row.get("tax_id_enc"),
            cipher,
            new PiiContext("policy_holders", "tax_id_enc", custId)));
    result.put(
        "credit_score",
        PolicyHolderPii.decryptInteger(
            (byte[]) row.get("credit_score_enc"),
            cipher,
            new PiiContext("policy_holders", "credit_score_enc", custId)));
    return result;
  }

  private String rowFingerprint(MigrationTable table, Map<String, Object> row) {
    return table == MigrationTable.POLICY_HOLDERS
        ? CanonicalRow.hmacSha256(
            table.sourceName(), table.columns(), row, sourceZone, fingerprintKey())
        : CanonicalRow.sha256(table.sourceName(), table.columns(), row, sourceZone);
  }

  private String externalRecordKey(MigrationTable table, String recordKey) {
    if (table != MigrationTable.POLICY_HOLDERS) {
      return recordKey;
    }
    return CanonicalRow.hmacSha256(
        table.sourceName(),
        List.of("cust_id"),
        Map.of("cust_id", recordKey),
        sourceZone,
        fingerprintKey());
  }

  private String recordKey(MigrationTable table, Map<String, Object> row) {
    return table.keyColumns().stream()
        .map(
            key -> {
              Object value = row.get(key.toLowerCase(Locale.ROOT));
              return value == null ? "\\N" : value.toString().stripTrailing();
            })
        .collect(java.util.stream.Collectors.joining("|"));
  }

  private void compareTotals(long runId, List<Difference> differences) {
    Map<Dimension, Totals> sourceTotals = new HashMap<>();
    Map<Dimension, Totals> targetTotals = new HashMap<>();
    Set<String> sourcePolicies = new HashSet<>();
    Set<String> targetPolicies = new HashSet<>();
    Set<String> sourcePremiums = new HashSet<>();
    Set<String> targetPremiums = new HashSet<>();
    Set<String> sourceCoverages = new HashSet<>();
    Set<String> targetCoverages = new HashSet<>();
    for (Db2Source.KeyRange range : ranges(MigrationTable.POLICIES)) {
      List<Map<String, Object>> legacyPolicies = sourceRows(MigrationTable.POLICIES, range);
      List<Map<String, Object>> shadowPolicies = targetRows(MigrationTable.POLICIES, range);
      Map<String, Dimension> legacyDimensions = new HashMap<>();
      Map<String, Dimension> shadowDimensions = new HashMap<>();
      for (Map<String, Object> policy : legacyPolicies) {
        String key = recordKey(MigrationTable.POLICIES, policy);
        if (sourcePolicies.add(key)) {
          Dimension dimension = dimension(policy);
          legacyDimensions.put((String) policy.get("policy_number"), dimension);
          sourceTotals.computeIfAbsent(dimension, ignored -> new Totals()).addPolicy(policy);
        }
      }
      for (Map<String, Object> policy : shadowPolicies) {
        String key = recordKey(MigrationTable.POLICIES, policy);
        if (targetPolicies.add(key)) {
          Dimension dimension = dimension(policy);
          shadowDimensions.put((String) policy.get("policy_number"), dimension);
          targetTotals.computeIfAbsent(dimension, ignored -> new Totals()).addPolicy(policy);
        }
      }
      addChildTotals(
          range,
          MigrationTable.PREMIUMS,
          "total_premium",
          legacyDimensions,
          sourcePremiums,
          sourceTotals,
          true);
      addChildTotals(
          range,
          MigrationTable.COVERAGES,
          "coverage_limit",
          legacyDimensions,
          sourceCoverages,
          sourceTotals,
          true);
      addChildTotals(
          range,
          MigrationTable.PREMIUMS,
          "total_premium",
          shadowDimensions,
          targetPremiums,
          targetTotals,
          false);
      addChildTotals(
          range,
          MigrationTable.COVERAGES,
          "coverage_limit",
          shadowDimensions,
          targetCoverages,
          targetTotals,
          false);
    }
    Set<Dimension> dimensions = new LinkedHashSet<>(sourceTotals.keySet());
    dimensions.addAll(targetTotals.keySet());
    for (Dimension dimension : dimensions) {
      Totals legacy = sourceTotals.getOrDefault(dimension, new Totals());
      Totals shadow = targetTotals.getOrDefault(dimension, new Totals());
      String[] names = {
        "policies.total_premium",
        "policies.coverage_limit",
        "premiums.total_premium",
        "coverages.coverage_limit"
      };
      BigDecimal[] sourceValues = legacy.values();
      BigDecimal[] targetValues = shadow.values();
      for (int index = 0; index < names.length; index++) {
        if (sourceValues[index].compareTo(targetValues[index]) != 0) {
          differences.add(
              new Difference(
                  runId,
                  "totals",
                  "TOTAL",
                  null,
                  "policy_type="
                      + dimension.policyType()
                      + ";branch_code="
                      + dimension.branchCode()
                      + ";metric="
                      + names[index],
                  decimal(sourceValues[index]),
                  decimal(targetValues[index]),
                  false));
        }
      }
    }
  }

  private void addChildTotals(
      Db2Source.KeyRange range,
      MigrationTable table,
      String amountColumn,
      Map<String, Dimension> dimensions,
      Set<String> seen,
      Map<Dimension, Totals> totals,
      boolean fromSource) {
    List<Map<String, Object>> rows =
        fromSource ? sourceRows(table, range) : targetRows(table, range);
    for (Map<String, Object> row : rows) {
      String key = recordKey(table, row);
      if (seen.add(key)) {
        Dimension dimension = dimensions.get((String) row.get("policy_number"));
        if (dimension != null) {
          totals
              .computeIfAbsent(dimension, ignored -> new Totals())
              .addChild(table, amountColumn, row);
        }
      }
    }
  }

  private List<Map<String, Object>> sourceRows(MigrationTable table, Db2Source.KeyRange range) {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<Object> cursor = List.of();
    while (true) {
      List<Map<String, Object>> page =
          source.readPage(table, cursor, range.minimum(), range.maximum(), PAGE_SIZE);
      if (page.isEmpty()) {
        return rows;
      }
      rows.addAll(page);
      Map<String, Object> last = page.getLast();
      cursor =
          table.keyColumns().stream().map(key -> last.get(key.toLowerCase(Locale.ROOT))).toList();
    }
  }

  private List<Map<String, Object>> targetRows(MigrationTable table, Db2Source.KeyRange range) {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<Object> cursor = List.of();
    while (true) {
      List<Map<String, Object>> page = targetPage(table, range, cursor);
      if (page.isEmpty()) {
        return rows;
      }
      for (Map<String, Object> row : page) {
        rows.add(table == MigrationTable.POLICY_HOLDERS ? decryptHolder(row) : row);
      }
      Map<String, Object> last = page.getLast();
      cursor =
          table.keyColumns().stream().map(key -> last.get(key.toLowerCase(Locale.ROOT))).toList();
    }
  }

  private Dimension dimension(Map<String, Object> policy) {
    return new Dimension(
        trimNullable((String) policy.get("policy_type")),
        trimNullable((String) policy.get("branch_code")));
  }

  private static String trimNullable(String value) {
    return value == null ? null : trimChar(value);
  }

  private static String trimChar(String value) {
    return value == null ? null : value.replaceFirst(" +$", "");
  }

  private static String decimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private SecretKey fingerprintKey(String configured, PiiProperties piiProperties) {
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("pas.migration.recon.fingerprint-key must be configured");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(configured);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "pas.migration.recon.fingerprint-key must be base64-encoded", exception);
    }
    if (bytes.length != 32) {
      throw new IllegalStateException(
          "pas.migration.recon.fingerprint-key must decode to 32 bytes");
    }
    for (String piiEncoded :
        new String[] {piiProperties.encryption().activeKey(), piiProperties.blindIndex().key()}) {
      if (piiEncoded != null && !piiEncoded.isBlank()) {
        byte[] piiBytes = Base64.getDecoder().decode(piiEncoded);
        if (MessageDigest.isEqual(bytes, piiBytes)) {
          throw new IllegalStateException(
              "Reconciliation fingerprint key must be distinct from PII keys");
        }
      }
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  private SecretKey fingerprintKey() {
    SecretKey key = fingerprintKey;
    if (key == null) {
      synchronized (this) {
        key = fingerprintKey;
        if (key == null) {
          key = fingerprintKey(fingerprintKeyConfiguration, piiProperties);
          fingerprintKey = key;
        }
      }
    }
    return key;
  }

  private void writeReports(
      long runId,
      OffsetDateTime startedAt,
      String status,
      Map<String, Object> counts,
      List<Difference> differences)
      throws IOException {
    Path directory = Path.of(properties.recon().reportDir());
    Files.createDirectories(directory);
    String date = startedAt.toLocalDate().toString();
    Path json = directory.resolve("recon-" + date + "-" + runId + ".json");
    Path markdown = directory.resolve("recon-" + date + "-" + runId + ".md");
    objectMapper.writeValue(
        json.toFile(), new Report(runId, startedAt, status, counts, differences));
    StringBuilder content =
        new StringBuilder("# Reconciliation ")
            .append(runId)
            .append("\n\nStatus: ")
            .append(status)
            .append("\n\n## Counts\n\n")
            .append("```json\n")
            .append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(counts))
            .append("\n```\n\n## Differences\n\n")
            .append("| Table | Kind | Key | Dimension | Source | Target |\n")
            .append("|---|---|---|---|---|---|\n");
    for (Difference difference : differences) {
      content
          .append("| ")
          .append(difference.tableName())
          .append(" | ")
          .append(difference.kind())
          .append(" | ")
          .append(safeMarkdown(difference.recordKey()))
          .append(" | ")
          .append(safeMarkdown(difference.dimension()))
          .append(" | ")
          .append(safeMarkdown(difference.sourceValue()))
          .append(" | ")
          .append(safeMarkdown(difference.targetValue()))
          .append(" |\n");
    }
    Files.writeString(markdown, content);
  }

  private void persistDifferences(long runId, List<Difference> differences) {
    for (Difference difference : differences) {
      jdbc.update(
          """
          INSERT INTO pas_batch.recon_difference
              (run_id, table_name, kind, record_key, dimension, source_value, target_value)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """,
          runId,
          difference.tableName(),
          difference.kind(),
          difference.recordKey(),
          difference.dimension(),
          difference.sourceValue(),
          difference.targetValue());
    }
  }

  private void updateMetrics(List<Difference> differences, OffsetDateTime startedAt) {
    differenceGauges.values().forEach(gauge -> gauge.set(0));
    Map<String, Long> grouped =
        differences.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    difference -> difference.tableName() + ":" + difference.kind(),
                    java.util.stream.Collectors.counting()));
    grouped.forEach(
        (key, value) -> {
          String[] parts = key.split(":", 2);
          String gaugeKey = parts[0] + ":" + parts[1];
          AtomicLong gauge =
              differenceGauges.computeIfAbsent(
                  gaugeKey,
                  ignored ->
                      metrics.gauge(
                          "pas.recon.differences",
                          Tags.of("table", parts[0], "kind", parts[1]),
                          new AtomicLong()));
          gauge.set(value);
        });
    lastRunTimestamp.set(startedAt.toEpochSecond());
  }

  private static String safeMarkdown(String value) {
    return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
  }

  private record TableCounts(long source, long target) {}

  private record Dimension(String policyType, String branchCode) {}

  private static final class Totals {
    private BigDecimal policyPremium = BigDecimal.ZERO;
    private BigDecimal policyCoverage = BigDecimal.ZERO;
    private BigDecimal premium = BigDecimal.ZERO;
    private BigDecimal coverage = BigDecimal.ZERO;

    void addPolicy(Map<String, Object> row) {
      policyPremium = policyPremium.add(value(row.get("total_premium")));
      policyCoverage = policyCoverage.add(value(row.get("coverage_limit")));
    }

    void addChild(MigrationTable table, String amountColumn, Map<String, Object> row) {
      BigDecimal amount = value(row.get(amountColumn));
      if (table == MigrationTable.PREMIUMS) {
        premium = premium.add(amount);
      } else {
        coverage = coverage.add(amount);
      }
    }

    BigDecimal[] values() {
      return new BigDecimal[] {policyPremium, policyCoverage, premium, coverage};
    }

    private static BigDecimal value(Object value) {
      return value instanceof BigDecimal decimal
          ? decimal
          : value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
  }

  public record Difference(
      long runId,
      String tableName,
      String kind,
      String recordKey,
      String dimension,
      String sourceValue,
      String targetValue,
      boolean unused) {}

  public record Result(long runId, String status, int differenceCount, Map<String, Object> counts) {
    public Result {
      counts = Map.copyOf(counts);
    }
  }

  public record Report(
      long runId,
      OffsetDateTime startedAt,
      String status,
      Map<String, Object> counts,
      List<Difference> differences) {
    public Report {
      counts = Map.copyOf(counts);
      differences = List.copyOf(differences);
    }
  }
}
