package com.acme.insurance.pas.policy.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.common.pii.PiiCipher;
import com.acme.insurance.pas.policy.TestPiiKeys;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PiiConvertersTest {

  private final String key = TestPiiKeys.randomKey();
  private final PiiConfiguration config = new PiiConfiguration();
  private final PiiCipher cipher =
      config.piiCipher(new PiiProperties(key, TestPiiKeys.randomKey(), "k1"));

  @Test
  void convertersRoundTripAndHandleNull() {
    EncryptedStringConverter s = new EncryptedStringConverter(cipher);
    EncryptedLocalDateConverter d = new EncryptedLocalDateConverter(cipher);
    EncryptedIntegerConverter i = new EncryptedIntegerConverter(cipher);
    assertThat(s.convertToEntityAttribute(s.convertToDatabaseColumn("4821"))).isEqualTo("4821");
    LocalDate dob = LocalDate.of(1971, 3, 14);
    assertThat(d.convertToDatabaseColumn(dob)).doesNotContain("1971");
    assertThat(d.convertToEntityAttribute(d.convertToDatabaseColumn(dob))).isEqualTo(dob);
    assertThat(i.convertToEntityAttribute(i.convertToDatabaseColumn(731))).isEqualTo(731);
    assertThat(s.convertToDatabaseColumn(null)).isNull();
    assertThat(d.convertToEntityAttribute(null)).isNull();
    assertThat(i.convertToDatabaseColumn(null)).isNull();
  }

  @Test
  void configurationRejectsMissingShortOrReusedKeys() {
    assertThatThrownBy(() -> config.piiCipher(new PiiProperties(null, key, "k1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pas.pii.encryption-key");
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    assertThatThrownBy(() -> config.piiCipher(new PiiProperties(shortKey, key, "k1")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config.piiCipher(new PiiProperties(key, key, "k1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must differ");
    assertThatThrownBy(() -> config.blindIndexer(new PiiProperties(key, "%%%", "k1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("%%%");
  }

  @Test
  void propertiesToStringHidesKeys() {
    assertThat(new PiiProperties(key, TestPiiKeys.randomKey(), "k1").toString())
        .doesNotContain(key)
        .contains("k1");
  }
}
