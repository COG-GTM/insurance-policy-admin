package com.acme.insurance.pas.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds a single-row INSERT. {@link #set} always binds the value, so a null in a nullable column
 * is stored as SQL NULL even when the column has a default. {@link #setOrDefault} is for the {@code
 * NOT NULL DEFAULT} columns of the ACMEINS DDL (status 'PN', 'SYSTEM', CURRENT TIMESTAMP, ...): a
 * null leaves the column out so the database default applies, as for the legacy programs. Table and
 * column names are constants of the repositories.
 */
final class Insert {

  private final String table;
  private final Map<String, Object> values = new LinkedHashMap<>();

  Insert(String table) {
    this.table = table;
  }

  Insert set(String column, Object value) {
    values.put(column, value);
    return this;
  }

  Insert setOrDefault(String column, Object value) {
    if (value != null) {
      values.put(column, value);
    }
    return this;
  }

  int execute(JdbcClient jdbc) {
    String sql =
        "INSERT INTO "
            + table
            + " ("
            + String.join(", ", values.keySet())
            + ") VALUES ("
            + String.join(", ", Collections.nCopies(values.size(), "?"))
            + ")";
    return jdbc.sql(sql).params(new ArrayList<>(values.values())).update();
  }
}
