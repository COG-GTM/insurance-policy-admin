package com.acme.insurance.pas.batch.migration.bulk;

import com.acme.insurance.pas.batch.migration.MigrationProperties;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import com.acme.insurance.pas.pii.BlindIndex;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiContext;
import com.acme.insurance.pas.pii.PolicyHolderPii;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are retained for the lifetime of this writer.")
public final class MigrationRowWriter implements ItemWriter<MigrationRow> {
  private final JdbcTemplate jdbc;
  private final PiiCipher cipher;
  private final BlindIndex blindIndex;
  private final ZoneId sourceZone;
  private final MeterRegistry metrics;

  public MigrationRowWriter(
      JdbcTemplate jdbc,
      PiiCipher cipher,
      BlindIndex blindIndex,
      MigrationProperties properties,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.blindIndex = blindIndex;
    sourceZone = ZoneId.of(properties.source().zone());
    this.metrics = metrics;
  }

  @Override
  public void write(Chunk<? extends MigrationRow> chunk) {
    for (MigrationRow row : chunk) {
      upsert(row.table(), row.values());
    }
  }

  public void upsert(MigrationTable table, Map<String, Object> sourceRow) {
    List<String> columns = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    if (table == MigrationTable.TERRITORY_FACTORS) {
      columns.add("factor_set");
      values.add("DB2");
    }
    for (String sourceColumn : table.columns()) {
      String sourceName = sourceColumn.toLowerCase(java.util.Locale.ROOT);
      Object value = sourceRow.get(sourceName);
      if (table == MigrationTable.POLICY_HOLDERS && isRegulated(sourceName)) {
        addEncryptedHolderValue(sourceName, value, sourceRow, columns, values);
      } else {
        columns.add(targetColumn(table, sourceName));
        values.add(convertTimestamp(value));
      }
    }
    if (table == MigrationTable.UNDERWRITING_DECISIONS) {
      columns.add("source_system");
      values.add("DB2");
    }

    List<String> keys = conflictKeys(table);
    String updates =
        columns.stream()
            .filter(column -> !keys.contains(column))
            .map(column -> column + " = EXCLUDED." + column)
            .collect(java.util.stream.Collectors.joining(", "));
    String distinct =
        columns.stream()
            .filter(column -> !keys.contains(column))
            .map(
                column ->
                    "pas."
                        + table.targetName()
                        + "."
                        + column
                        + " IS DISTINCT FROM EXCLUDED."
                        + column)
            .collect(java.util.stream.Collectors.joining(" OR "));
    if (table == MigrationTable.POLICIES) {
      updates += ", row_version = pas.policies.row_version + 1";
    }
    String conflictTarget = "(" + String.join(", ", keys) + ")";
    if (table == MigrationTable.UNDERWRITING_DECISIONS) {
      conflictTarget += " WHERE source_system = 'DB2'";
    }
    StringBuilder sql =
        new StringBuilder("INSERT INTO pas.")
            .append(table.targetName())
            .append(" (")
            .append(String.join(", ", columns))
            .append(") VALUES (")
            .append("?,".repeat(columns.size()));
    sql.setLength(sql.length() - 1);
    sql.append(") ON CONFLICT ").append(conflictTarget).append(" DO UPDATE SET ").append(updates);
    if (!distinct.isBlank()) {
      sql.append(" WHERE ").append(distinct);
    }
    int changed = jdbc.update(sql.toString(), values.toArray());
    metrics
        .counter(
            "pas.migration.bulk.rows",
            "table",
            table.targetName(),
            "result",
            changed == 0 ? "unchanged" : "applied")
        .increment();
  }

  private void addEncryptedHolderValue(
      String column,
      Object value,
      Map<String, Object> sourceRow,
      List<String> columns,
      List<Object> values) {
    String custId = ((String) sourceRow.get("cust_id")).stripTrailing();
    String encryptedColumn = column + "_enc";
    columns.add(encryptedColumn);
    PiiContext context = new PiiContext("policy_holders", encryptedColumn, custId);
    values.add(
        switch (column) {
          case "date_of_birth" -> PolicyHolderPii.encryptDate((LocalDate) value, cipher, context);
          case "credit_score" -> PolicyHolderPii.encryptInteger((Integer) value, cipher, context);
          default -> PolicyHolderPii.encryptString((String) value, cipher, context);
        });
    if (column.equals("tax_id")) {
      columns.add("tax_id_bidx");
      values.add(blindIndex.of((String) value));
    }
  }

  private static boolean isRegulated(String column) {
    return switch (column) {
      case "date_of_birth", "ssn_last4", "tax_id", "credit_score" -> true;
      default -> false;
    };
  }

  private static String targetColumn(MigrationTable table, String sourceColumn) {
    return sourceColumn;
  }

  private List<String> conflictKeys(MigrationTable table) {
    return switch (table) {
      case TERRITORY_FACTORS -> List.of("factor_set", "territory_code", "effective_date");
      default ->
          table.keyColumns().stream()
              .map(column -> column.toLowerCase(java.util.Locale.ROOT))
              .toList();
    };
  }

  private Object convertTimestamp(Object value) {
    return value instanceof LocalDateTime timestamp
        ? timestamp.atZone(sourceZone).toOffsetDateTime()
        : value;
  }
}
