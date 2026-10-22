package com.acme.insurance.pas.policy.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnderwritingDecisionRepositoryTest {
  @Test
  void keepsSeventyFiveEmojiIntact() {
    String reason = "😀".repeat(75);

    String result = UnderwritingDecisionRepository.legacyDecisionReason(reason);

    assertThat(result).isEqualTo(reason);
    assertThat(result.codePointCount(0, result.length())).isEqualTo(75);
  }

  @Test
  void truncatesAtOneHundredCodePointsWithoutSplittingSurrogatePair() {
    String reason = "a".repeat(99) + "😀" + "b";

    String result = UnderwritingDecisionRepository.legacyDecisionReason(reason);

    assertThat(reason.codePointCount(0, reason.length())).isEqualTo(101);
    assertThat(result).isEqualTo("a".repeat(99) + "😀");
    assertThat(result.codePointCount(0, result.length())).isEqualTo(100);
  }
}
