package com.acme.insurance.pas.common.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PiiCipherTest {

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  private final byte[] key = randomKey();
  private final PiiCipher cipher = new PiiCipher(key, "k1");

  @Test
  void roundTripsAndRandomisesIv() {
    String a = cipher.encrypt("123-45-6789");
    String b = cipher.encrypt("123-45-6789");
    assertThat(a).isNotEqualTo(b).doesNotContain("6789");
    assertThat(cipher.decrypt(a)).isEqualTo("123-45-6789");
    assertThat(cipher.decrypt(b)).isEqualTo("123-45-6789");
    assertThat(cipher.encrypt(null)).isNull();
    assertThat(cipher.decrypt(null)).isNull();
    assertThat(cipher.encrypt("")).isNotEmpty();
    assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
  }

  @Test
  void ciphertextFitsColumnForLargestField() {
    assertThat(cipher.encrypt("9".repeat(10)).length()).isLessThan(255);
  }

  @Test
  void detectsTamperingWrongKeyAndWrongKeyId() {
    String sealed = cipher.encrypt("4321");
    byte[] raw = Base64.getDecoder().decode(sealed);
    raw[raw.length - 1] ^= 1;
    String tampered = Base64.getEncoder().encodeToString(raw);
    assertThatThrownBy(() -> cipher.decrypt(tampered))
        .isInstanceOf(PiiCryptoException.class)
        .hasMessageNotContaining("4321");
    assertThatThrownBy(() -> new PiiCipher(randomKey(), "k1").decrypt(sealed))
        .isInstanceOf(PiiCryptoException.class);
    assertThatThrownBy(() -> new PiiCipher(key, "k2").decrypt(sealed))
        .isInstanceOf(PiiCryptoException.class)
        .hasMessageContaining("different key id");
    assertThatThrownBy(() -> cipher.decrypt("not base64!")).isInstanceOf(PiiCryptoException.class);
  }

  @Test
  void rejectsWrongKeySizes() {
    assertThatThrownBy(() -> new PiiCipher(new byte[16], "k1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PiiCipher(key, "")).isInstanceOf(IllegalArgumentException.class);
  }
}
