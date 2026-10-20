package com.acme.insurance.pas.batch.migration;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;

/** Maps a source row to a mutable map keyed by lower-case column name (DB2 reports upper case). */
public final class SourceRows implements RowMapper<Map<String, Object>> {

  public static final SourceRows INSTANCE = new SourceRows();

  private SourceRows() {}

  @Override
  public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
    }
    return row;
  }

  /** {@code SELECT col, ... FROM schema.table} for a migration table's source columns. */
  public static String selectAll(SourceDatabase source, MigrationTable table) {
    return "SELECT "
        + String.join(", ", table.sourceColumns()).toUpperCase(Locale.ROOT)
        + " FROM "
        + source.table(table.sourceTable());
  }
}
