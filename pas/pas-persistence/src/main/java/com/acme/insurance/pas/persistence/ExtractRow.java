package com.acme.insurance.pas.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One row of a feed query, keyed by upper-case column label in select order. Values are normalised
 * so DB2 and PostgreSQL rows compare equal: CHAR padding is stripped, DATE is a {@link
 * java.time.LocalDate}, SMALLINT/INTEGER are {@link Integer}, BIGINT/COUNT are {@link Long} and
 * DECIMAL stays {@link BigDecimal}.
 */
public record ExtractRow(Map<String, Object> values) {

  public ExtractRow {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public static ExtractRow of(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      values.put(meta.getColumnLabel(i).toUpperCase(Locale.ROOT), normalise(rs.getObject(i)));
    }
    return new ExtractRow(values);
  }

  public List<String> columns() {
    return List.copyOf(values.keySet());
  }

  public Object get(String column) {
    if (!values.containsKey(column)) {
      throw new IllegalArgumentException("no column " + column + " in " + values.keySet());
    }
    return values.get(column);
  }

  private static Object normalise(Object value) {
    return switch (value) {
      case null -> null;
      case String s -> s.stripTrailing();
      case Date d -> d.toLocalDate();
      case Timestamp t -> t.toLocalDateTime();
      case Short s -> Integer.valueOf(s);
      case Integer i -> i;
      case Long l -> l;
      case BigDecimal b -> b;
      case Number n -> new BigDecimal(n.toString());
      default -> value;
    };
  }
}
