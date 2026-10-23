package com.acme.insurance.pas.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PiiCipherTest {
  private static final PiiContext CONTEXT =
      new PiiContext("policy_holders", "tax_id_enc", "CUST000001");

  @Test
  void encryptsWithRandomIvAndBindsEnvelopeToContext() {
    PiiKey key = key("key-v1", 1, 1);
    PiiCipher cipher = new PiiCipher(new PiiKeyRing(key, List.of()));

    byte[] first = cipher.encrypt("987654320", CONTEXT);
    byte[] second = cipher.encrypt("987654320", CONTEXT);

    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decrypt(first, CONTEXT)).isEqualTo("987654320");
    assertThatThrownBy(
            () ->
                cipher.decrypt(
                    first, new PiiContext("policy_holders", "ssn_last4_enc", "CUST000001")))
        .isInstanceOf(PiiCryptoException.class);
  }

  @Test
  void supportsRotationAndRejectsUnknownKeysAndTampering() {
    PiiKey old = key("old", 1, 2);
    PiiCipher original = new PiiCipher(new PiiKeyRing(old, List.of()));
    byte[] envelope = original.encrypt("1980-04-17", CONTEXT);
    PiiCipher rotated = new PiiCipher(new PiiKeyRing(key("new", 2, 3), List.of(old)));

    assertThat(rotated.decrypt(envelope, CONTEXT)).isEqualTo("1980-04-17");
    assertThat((char) envelope[0]).isEqualTo('P');
    assertThat(envelope[1]).isEqualTo((byte) 1);
    assertThatThrownBy(
            () ->
                new PiiCipher(new PiiKeyRing(key("new", 2, 3), List.of()))
                    .decrypt(envelope, CONTEXT))
        .isInstanceOf(PiiCryptoException.class);
    envelope[envelope.length - 1] ^= 1;
    assertThatThrownBy(() -> rotated.decrypt(envelope, CONTEXT))
        .isInstanceOf(PiiCryptoException.class);
  }

  @Test
  void requiresIndependentBlindIndexKeyAndNormalizesLookupValues() {
    SecretKey encryption = secret(4);
    SecretKey blind = secret(5);
    PiiKey key = new PiiKey("key", 1, encryption);

    assertThat(PiiCrypto.create(new PiiKeyRing(key, List.of()), blind)).isNotNull();
    assertThatThrownBy(() -> PiiCrypto.create(new PiiKeyRing(key, List.of()), encryption))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distinct");
    BlindIndex index = new BlindIndex(blind);
    assertThat(index.of("12-3456789")).containsExactly(index.of("123456789"));
    assertThat(index.of("123456789")).isNotEqualTo(new BlindIndex(secret(6)).of("123456789"));
    assertThat(
            PiiCipher.envelopeIv(
                new PiiCipher(new PiiKeyRing(key, List.of())).encrypt("x", CONTEXT)))
        .hasSize(12);
  }

  @Test
  void rejectsNonAes256Keys() {
    assertThatThrownBy(() -> new PiiKey("key", 1, new SecretKeySpec(new byte[16], "AES")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AES-256");
  }

  private static PiiKey key(String id, int version, int seed) {
    return new PiiKey(id, version, secret(seed));
  }

  private static SecretKey secret(int seed) {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) seed);
    return new SecretKeySpec(bytes, "AES");
  }
}
