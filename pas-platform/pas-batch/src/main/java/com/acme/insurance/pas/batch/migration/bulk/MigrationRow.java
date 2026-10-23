package com.acme.insurance.pas.batch.migration.bulk;

import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record MigrationRow(MigrationTable table, Map<String, Object> values) {
  public MigrationRow {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
