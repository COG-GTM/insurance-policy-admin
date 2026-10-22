package com.acme.insurance.pas.batch.migration.source;

import com.acme.insurance.pas.batch.migration.MigrationProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Db2Source implements AutoCloseable {
  private final HikariDataSource pool;
  private final MigrationProperties properties;
  private final String schema;

  public Db2Source(MigrationProperties properties) {
    this.properties = properties;
    MigrationProperties.Source source = properties.source();
    if (source.url() == null || source.url().isBlank()) {
      throw new IllegalStateException("pas.migration.source.url must be configured");
    }
    if (source.username() == null || source.username().isBlank()) {
      throw new IllegalStateException("pas.migration.source.username must be configured");
    }
    if (source.password() == null || source.password().isBlank()) {
      throw new IllegalStateException("pas.migration.source.password must be configured");
    }
    schema = source.schema();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(jdbcUrl(source));
    config.setUsername(source.username());
    config.setPassword(source.password());
    config.setMaximumPoolSize(4);
    config.setReadOnly(true);
    config.setPoolName("pas-db2-source");
    pool = new HikariDataSource(config);
  }

  public static String jdbcUrl(MigrationProperties.Source source) {
    String url = source.url();
    if (source.ssl() && !url.toLowerCase(java.util.Locale.ROOT).contains("sslconnection=")) {
      url += (url.endsWith(";") ? "" : ";") + "sslConnection=true";
    }
    if (source.sslTrustStoreLocation() != null && !source.sslTrustStoreLocation().isBlank()) {
      url +=
          (url.endsWith(";") ? "" : ";")
              + "sslTrustStoreLocation="
              + source.sslTrustStoreLocation();
    }
    return url;
  }

  public String schema() {
    return schema;
  }

  @SuppressFBWarnings(
      value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      justification = "The schema comes from the validated MigrationProperties identifier.")
  public List<String> readCoveragePolicyKeys(String afterKey, int limit) {
    String sql =
        "SELECT DISTINCT RTRIM(POLICY_NUMBER) AS POLICY_NUMBER FROM "
            + schema
            + ".COVERAGES "
            + (afterKey == null ? "" : "WHERE RTRIM(POLICY_NUMBER) > ? ")
            + "ORDER BY POLICY_NUMBER FETCH FIRST ? ROWS ONLY";
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      int limitParameterIndex = 1;
      if (afterKey != null) {
        statement.setString(limitParameterIndex++, afterKey);
      }
      statement.setInt(limitParameterIndex, limit);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<String> keys = new ArrayList<>();
        while (resultSet.next()) {
          keys.add(resultSet.getString("POLICY_NUMBER"));
        }
        return keys;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read coverage policy keys", exception);
    }
  }

  public List<String> readPolicyKeys(String afterKey, int limit) {
    String sql =
        "SELECT RTRIM(POLICY_NUMBER) AS POLICY_NUMBER FROM "
            + schema
            + ".POLICIES "
            + (afterKey == null ? "" : "WHERE RTRIM(POLICY_NUMBER) > ? ")
            + "ORDER BY RTRIM(POLICY_NUMBER) FETCH FIRST ? ROWS ONLY";
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      int limitParameterIndex = 1;
      if (afterKey != null) {
        statement.setString(limitParameterIndex++, afterKey);
      }
      statement.setInt(limitParameterIndex, limit);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<String> keys = new ArrayList<>();
        while (resultSet.next()) {
          keys.add(resultSet.getString("POLICY_NUMBER"));
        }
        return keys;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read policy keys", exception);
    }
  }

  @SuppressFBWarnings(
      value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      justification =
          "SQL identifiers come only from the fixed MigrationTable enum; values are bound.")
  public List<Map<String, Object>> readPage(
      MigrationTable table,
      List<Object> afterKey,
      String inclusiveMinimum,
      String inclusiveMaximum,
      int limit) {
    StringBuilder sql = new StringBuilder(select(table));
    List<Parameter> parameters = new ArrayList<>();
    List<String> predicates = new ArrayList<>();
    if (inclusiveMinimum != null) {
      predicates.add(table.leadingKey() + " >= ?");
      parameters.add(new Parameter(inclusiveMinimum, Types.CHAR));
    }
    if (inclusiveMaximum != null) {
      predicates.add(table.leadingKey() + " <= ?");
      parameters.add(new Parameter(inclusiveMaximum, Types.CHAR));
    }
    if (table.changeColumn() != null) {
      // The delta reader supplies its watermark with a synthetic key bound.
      if (afterKey != null && afterKey.size() > table.keyColumns().size()) {
        predicates.add(table.changeColumn() + " > ?");
        parameters.add(new Parameter(afterKey.getFirst(), changeType(table)));
        afterKey = afterKey.subList(1, afterKey.size());
      }
    }
    appendKeyset(table, afterKey, predicates, parameters);
    if (!predicates.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", predicates));
    }
    sql.append(" ORDER BY ").append(String.join(", ", table.keyColumns()));
    sql.append(" FETCH FIRST ? ROWS ONLY");
    parameters.add(new Parameter(limit, Types.INTEGER));
    return execute(sql.toString(), parameters, table);
  }

  @SuppressFBWarnings(
      value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      justification =
          "SQL identifiers come only from the fixed MigrationTable enum; values are bound.")
  public List<Map<String, Object>> readChanges(
      MigrationTable table,
      Object watermark,
      LocalDateTime throughTimestamp,
      List<Object> afterKey,
      int limit) {
    if (table.changeColumn() == null) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder(select(table));
    List<Parameter> parameters = new ArrayList<>();
    sql.append(" WHERE ")
        .append(table.changeColumn())
        .append(table == MigrationTable.PREMIUMS ? " >= ?" : " > ?");
    Object sourceWatermark =
        table == MigrationTable.PREMIUMS && watermark instanceof LocalDateTime timestamp
            ? timestamp.toLocalDate()
            : watermark;
    parameters.add(new Parameter(sourceWatermark, changeType(table)));
    sql.append(" AND ").append(table.changeColumn()).append(" <= ?");
    Object sourceThrough =
        table == MigrationTable.PREMIUMS ? throughTimestamp.toLocalDate() : throughTimestamp;
    parameters.add(new Parameter(sourceThrough, changeType(table)));
    if (afterKey != null && !afterKey.isEmpty()) {
      List<String> conditions = new ArrayList<>();
      List<Parameter> keyParameters = new ArrayList<>();
      appendKeyset(table, afterKey, conditions, keyParameters);
      sql.append(" AND ").append(conditions.getFirst());
      parameters.addAll(keyParameters);
    }
    sql.append(" ORDER BY ").append(String.join(", ", table.keyColumns()));
    sql.append(" FETCH FIRST ? ROWS ONLY");
    parameters.add(new Parameter(limit, Types.INTEGER));
    return execute(sql.toString(), parameters, table);
  }

  @SuppressFBWarnings(
      value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      justification =
          "SQL identifiers come only from the fixed MigrationTable enum; values are bound.")
  public List<Map<String, Object>> readByLeadingKeys(
      MigrationTable table, List<String> keys, int limit) {
    if (keys.isEmpty()) {
      return List.of();
    }
    List<Parameter> parameters = new ArrayList<>();
    parameters.addAll(keys.stream().map(key -> new Parameter(key, Types.CHAR)).toList());
    parameters.add(new Parameter(limit, Types.INTEGER));
    String placeholders = "?,".repeat(keys.size());
    String sql =
        select(table)
            + " WHERE "
            + table.leadingKey()
            + " IN ("
            + placeholders.substring(0, placeholders.length() - 1)
            + ") ORDER BY "
            + String.join(", ", table.keyColumns())
            + " FETCH FIRST ? ROWS ONLY";
    return execute(sql, parameters, table);
  }

  @SuppressFBWarnings(
      value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      justification = "Table and column identifiers are fixed; query values are bound.")
  public List<Map<String, Object>> readPremiumsForDate(
      LocalDate businessDate, List<Object> afterKey, int limit) {
    MigrationTable table = MigrationTable.PREMIUMS;
    StringBuilder sql = new StringBuilder(select(table)).append(" WHERE CALC_DATE = ?");
    List<Parameter> parameters = new ArrayList<>();
    parameters.add(new Parameter(businessDate, Types.DATE));
    if (afterKey != null && !afterKey.isEmpty()) {
      List<String> conditions = new ArrayList<>();
      appendKeyset(table, afterKey, conditions, parameters);
      sql.append(" AND ").append(conditions.getFirst());
    }
    sql.append(" ORDER BY ").append(String.join(", ", table.keyColumns()));
    sql.append(" FETCH FIRST ? ROWS ONLY");
    parameters.add(new Parameter(limit, Types.INTEGER));
    return execute(sql.toString(), parameters, table);
  }

  public List<KeyRange> keyRanges(MigrationTable table, int partitions) {
    String sql =
        "SELECT MIN(source_key), MAX(source_key) FROM ("
            + "SELECT source_key, NTILE(?) OVER (ORDER BY source_key) AS partition_no "
            + "FROM (SELECT DISTINCT "
            + table.leadingKey()
            + " AS source_key FROM "
            + qualified(table)
            + ") distinct_keys) sampled "
            + "GROUP BY partition_no ORDER BY partition_no FETCH FIRST ? ROWS ONLY";
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, Math.max(1, partitions));
      statement.setInt(2, Math.max(1, partitions));
      statement.setFetchSize(properties.source().fetchSize());
      try (ResultSet result = statement.executeQuery()) {
        List<KeyRange> ranges = new ArrayList<>();
        while (result.next()) {
          ranges.add(
              new KeyRange(
                  result.getString(1).stripTrailing(), result.getString(2).stripTrailing()));
        }
        return ranges;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to calculate DB2 migration key ranges", exception);
    }
  }

  public LocalDateTime currentTimestamp() {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT CURRENT_TIMESTAMP FROM SYSIBM.SYSDUMMY1")) {
      statement.setFetchSize(1);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new IllegalStateException("DB2 CURRENT TIMESTAMP query returned no row");
        }
        Timestamp timestamp = result.getTimestamp(1);
        return timestamp.toLocalDateTime();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read DB2 current timestamp", exception);
    }
  }

  private List<Map<String, Object>> execute(
      String sql, List<Parameter> parameters, MigrationTable table) {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setFetchSize(properties.source().fetchSize());
      for (int index = 0; index < parameters.size(); index++) {
        Parameter parameter = parameters.get(index);
        statement.setObject(index + 1, parameter.value(), parameter.sqlType());
      }
      try (ResultSet result = statement.executeQuery()) {
        ResultSetMetaData metadata = result.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (result.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int column = 1; column <= metadata.getColumnCount(); column++) {
            Object value = result.getObject(column);
            int type = metadata.getColumnType(column);
            if (value instanceof String text && type == Types.CHAR) {
              value = text.replaceFirst(" +$", "");
            } else if (value instanceof java.sql.Date date) {
              value = date.toLocalDate();
            } else if (value instanceof Timestamp timestamp) {
              value = timestamp.toLocalDateTime();
            } else if (value instanceof java.sql.Time time) {
              value = time.toLocalTime();
            }
            row.put(metadata.getColumnLabel(column).toLowerCase(java.util.Locale.ROOT), value);
          }
          rows.add(java.util.Collections.unmodifiableMap(row));
        }
        return rows;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to read DB2 migration table " + table.sourceName(), exception);
    }
  }

  private String select(MigrationTable table) {
    return "SELECT " + String.join(", ", table.columns()) + " FROM " + qualified(table);
  }

  private String qualified(MigrationTable table) {
    return schema + "." + table.sourceName();
  }

  private static void appendKeyset(
      MigrationTable table,
      List<Object> afterKey,
      List<String> predicates,
      List<Parameter> parameters) {
    if (afterKey == null || afterKey.isEmpty()) {
      return;
    }
    if (afterKey.size() != table.keyColumns().size()) {
      throw new IllegalArgumentException("Keyset must contain every primary-key column");
    }
    List<String> alternatives = new ArrayList<>();
    for (int keyIndex = 0; keyIndex < afterKey.size(); keyIndex++) {
      List<String> parts = new ArrayList<>();
      for (int prior = 0; prior < keyIndex; prior++) {
        parts.add(table.keyColumns().get(prior) + " = ?");
        parameters.add(new Parameter(afterKey.get(prior), table.keyTypes().get(prior)));
      }
      parts.add(table.keyColumns().get(keyIndex) + " > ?");
      parameters.add(new Parameter(afterKey.get(keyIndex), table.keyTypes().get(keyIndex)));
      alternatives.add("(" + String.join(" AND ", parts) + ")");
    }
    predicates.add("(" + String.join(" OR ", alternatives) + ")");
  }

  private static int changeType(MigrationTable table) {
    return table == MigrationTable.PREMIUMS ? Types.DATE : Types.TIMESTAMP;
  }

  @Override
  public void close() {
    pool.close();
  }

  private record Parameter(Object value, int sqlType) {}

  public record KeyRange(String minimum, String maximum) {}
}
