package com.acme.insurance.pas.pii;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record PiiContext(String table, String column, String rowKey) {
  public PiiContext {
    Objects.requireNonNull(table);
    Objects.requireNonNull(column);
    Objects.requireNonNull(rowKey);
  }

  byte[] additionalData() {
    return (table + "." + column + ":" + rowKey).getBytes(StandardCharsets.UTF_8);
  }
}
