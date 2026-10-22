package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.Coded;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;

final class JdbcValues {
  private JdbcValues() {}

  static String charValue(ResultSet row, String column) throws SQLException {
    String value = row.getString(column);
    return trimChar(value);
  }

  static String trimChar(String value) {
    return value == null ? null : value.replaceFirst(" +$", "");
  }

  static LocalDate date(ResultSet row, String column) throws SQLException {
    return row.getObject(column, LocalDate.class);
  }

  static OffsetDateTime timestamp(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class);
  }

  static Integer integer(ResultSet row, String column) throws SQLException {
    Number value = (Number) row.getObject(column);
    return value == null ? null : value.intValue();
  }

  static <E extends Enum<E> & com.acme.insurance.pas.domain.CobolCode> Coded<E> coded(
      ResultSet row, String column, Class<E> type) throws SQLException {
    String value = charValue(row, column);
    return value == null ? null : Coded.of(type, value);
  }
}
