package com.acme.insurance.pas.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParityHarnessTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void javaValuePinsArrayAtConfiguredField() throws Exception {
    KnownDifferences.Entry entry =
        new KnownDifferences.Entry(
            "UNDWRT", "receive-failure", "outputs.decision", "Q-44", "reason", List.of());

    assertThat(
            ParityHarness.javaValueMatchesAtField(
                entry, MAPPER.readTree("{\"outputs\":{\"decision\":[]}}")))
        .isTrue();
    assertThat(
            ParityHarness.javaValueMatchesAtField(
                entry, MAPPER.readTree("{\"outputs\":{\"decision\":[{\"riskScore\":401}]}}")))
        .isFalse();
  }
}
