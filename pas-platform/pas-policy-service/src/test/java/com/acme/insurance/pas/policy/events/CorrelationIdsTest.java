package com.acme.insurance.pas.policy.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationIdsTest {
  @Test
  void preservesValidCorrelationIdsUpTo64Characters() {
    String correlationId = "aZ09._-" + "x".repeat(57);

    assertThat(CorrelationIds.fromHeader(correlationId)).isEqualTo(correlationId);
  }

  @Test
  void replacesMissingOrInvalidCorrelationIdsWithUuid() {
    for (String value : new String[] {null, "", "invalid id", "x".repeat(65)}) {
      UUID.fromString(CorrelationIds.fromHeader(value));
    }
  }
}
