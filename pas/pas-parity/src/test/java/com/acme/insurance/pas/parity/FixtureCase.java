package com.acme.insurance.pas.parity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

/**
 * One fixture case from {@code parity/fixtures/<suite>.json}. {@code input} uses the same names as
 * the COBOL stubs (environment {@code PX_<NAME>}); {@code ROWS} holds cursor rows.
 */
public record FixtureCase(
    String suite,
    String id,
    String description,
    LocalDate runDate,
    JsonNode input,
    List<String> tags,
    List<String> quirks) {

  public FixtureCase {
    input = input.deepCopy();
    tags = List.copyOf(tags);
    quirks = List.copyOf(quirks);
  }

  /** Text input value, or the default when absent. */
  public String text(JsonNode node, String name, String defaultValue) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() ? defaultValue : value.asText();
  }
}
