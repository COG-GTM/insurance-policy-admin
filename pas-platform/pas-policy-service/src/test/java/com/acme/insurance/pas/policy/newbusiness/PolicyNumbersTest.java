package com.acme.insurance.pas.policy.newbusiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PolicyNumbersTest {
  @Test
  void formatsModernNumbersAndRejectsSequencesOutsideTheNineDigitField() {
    assertThat(PolicyNumbers.modern(1_000_000)).isEqualTo("POL001000000");
    assertThat(PolicyNumbers.modern(999_999_999)).isEqualTo("POL999999999");
    assertThatThrownBy(() -> PolicyNumbers.modern(1_000_000_000))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void preservesTheLegacyTrailingDigitCollision() {
    for (long sequence = 1_000_000; sequence <= 1_000_009; sequence++) {
      assertThat(PolicyNumbers.legacy(sequence)).isEqualTo("POL000100000");
    }
  }
}
