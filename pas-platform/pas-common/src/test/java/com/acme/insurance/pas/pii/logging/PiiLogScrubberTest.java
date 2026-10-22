package com.acme.insurance.pas.pii.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiLogScrubberTest {
  @Test
  void masksSensitiveAssignmentsAndIdentifiers() {
    assertThat(PiiLogScrubber.scrub("{\"taxId\":\"123456789\"}")).isEqualTo("{\"taxId\":\"****\"}");
    assertThat(PiiLogScrubber.scrub("{\"lastName\":\"O\\\"Brien-Smith\"}"))
        .isEqualTo("{\"lastName\":\"****\"}")
        .doesNotContain("Brien");
    assertThat(PiiLogScrubber.scrub("{\"email\":\"jane doe@example.test\"}"))
        .isEqualTo("{\"email\":\"****\"}");
    assertThat(PiiLogScrubber.scrub("addrLine1=123 Main Street")).isEqualTo("addrLine1=****");
    assertThat(PiiLogScrubber.scrub("creditScore=712")).isEqualTo("creditScore=****");
    assertThat(PiiLogScrubber.scrub("DOB: 19800417")).isEqualTo("DOB: ****");
    assertThat(PiiLogScrubber.scrub("ssn 123-45-6789 and EIN 12-3456789"))
        .isEqualTo("ssn **** and EIN ****");
    assertThat(PiiLogScrubber.scrub(null)).isNull();
  }
}
