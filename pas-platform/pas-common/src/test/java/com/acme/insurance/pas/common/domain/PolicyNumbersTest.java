package com.acme.insurance.pas.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyNumbersTest {

  @Test
  void newBusinessNumbersKeepTheFormatAndNeverRepeat() {
    assertThat(PolicyNumbers.forNewBusiness(1_000_000)).isEqualTo("POL001000000").hasSize(12);
    assertThat(PolicyNumbers.forNewBusiness(999_999_999)).isEqualTo("POL999999999");
    Set<String> seen = new HashSet<>();
    for (long seq = 1_000_000; seq < 1_000_100; seq++) {
      assertThat(seen.add(PolicyNumbers.forNewBusiness(seq))).isTrue();
    }
  }

  @Test
  void newBusinessNumbersCannotCollideWithMigratedParityNumbers() {
    long migratedUpTo = 1_234_567;
    Set<String> migrated = new HashSet<>();
    for (long seq = 1_000_000; seq <= migratedUpTo; seq++) {
      migrated.add(PolicyNumbers.fromSequence(seq));
    }
    for (long seq = migratedUpTo + 1; seq < migratedUpTo + 10_000; seq++) {
      assertThat(migrated).doesNotContain(PolicyNumbers.forNewBusiness(seq));
    }
  }

  @Test
  void rejectsValuesOutsideNineDigits() {
    assertThatThrownBy(() -> PolicyNumbers.forNewBusiness(1_000_000_000L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PolicyNumbers.forNewBusiness(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
