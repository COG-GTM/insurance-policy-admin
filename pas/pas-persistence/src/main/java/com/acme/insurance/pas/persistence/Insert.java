package com.acme.insurance.pas.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds an INSERT that leaves out null values, so the column defaults of the ACMEINS DDL (status
 * 'PN', 'SYSTEM', CURRENT TIMESTAMP, ...) apply exactly as they do for the legacy programs, and
 * columns without a default stay null. Table and column names are constants of the repositories.
 */
final class Insert {

  private final String table;
  private final Map<String, Object> values = new LinkedHashMap<>();

  Insert(String table) {
    this.table = table;
  }

  Insert set(String column, Object value) {
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
