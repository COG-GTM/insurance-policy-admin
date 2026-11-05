package com.acme.insurance.pas.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Reads ACMEINS columns into domain values. */
final class Columns {

  private Columns() {}

  /** CHAR(n) columns come back blank-padded; the domain keeps values without the padding. */
  static String text(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    return value == null ? null : value.stripTrailing();
  }

  static Integer integer(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
    return rs.getBigDecimal(column);
  }

  static LocalDate date(ResultSet rs, String column) throws SQLException {
    Date value = rs.getDate(column);
    return value == null ? null : value.toLocalDate();
  }

  static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toLocalDateTime();
  }
}
