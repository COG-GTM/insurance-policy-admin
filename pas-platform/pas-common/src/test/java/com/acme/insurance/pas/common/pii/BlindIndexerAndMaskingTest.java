package com.acme.insurance.pas.common.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BlindIndexerAndMaskingTest {

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  @Test
  void blindIndexIsDeterministicNormalisedAndPurposeSeparated() {
    byte[] key = randomKey();
    BlindIndexer indexer = new BlindIndexer(key);
    String a = indexer.index("tax", "12-3456789");
    assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(new BlindIndexer(key).index("tax", "123456789 ")).isEqualTo(a);
    assertThat(indexer.index("ssn", "12-3456789")).isNotEqualTo(a);
    assertThat(new BlindIndexer(randomKey()).index("tax", "12-3456789")).isNotEqualTo(a);
    assertThat(indexer.index("tax", null)).isNull();
    assertThatThrownBy(() -> new BlindIndexer(new byte[16]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void masksRegulatedFields() {
    assertThat(PiiMasking.ssnLast4("6789")).isEqualTo("***9");
    assertThat(PiiMasking.taxId("123456789")).isEqualTo("*******89");
    assertThat(PiiMasking.taxId("1")).isEqualTo("*");
    assertThat(PiiMasking.dateOfBirth(LocalDate.of(1980, 7, 4))).isEqualTo("1980-**-**");
    assertThat(PiiMasking.creditScore(720)).isEqualTo("[REDACTED]");
    assertThat(PiiMasking.ssnLast4(null)).isNull();
    assertThat(PiiMasking.dateOfBirth(null)).isNull();
  }
}
