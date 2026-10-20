package com.acme.insurance.pas.common.pii;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic HMAC-SHA256 blind index for equality lookup on encrypted columns. Each index is
 * domain-separated by a purpose label so the same value hashes differently per column. Uses a key
 * separate from the encryption key.
 */
public final class BlindIndexer {

  private final byte[] key;

  public BlindIndexer(byte[] key) {
    Objects.requireNonNull(key, "key");
    if (key.length < 32) {
      throw new IllegalArgumentException("blind-index key must be at least 256 bits");
    }
    this.key = key.clone();
  }

  /** Lower-case hex HMAC-SHA256 of {@code purpose + 0x00 + normalized value}. */
  public String index(String purpose, String value) {
    if (value == null) {
      return null;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      mac.update(purpose.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) 0);
      return HexFormat.of()
          .formatHex(mac.doFinal(normalize(value).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new PiiCryptoException("blind index computation failed", e);
    }
  }

  /** Strips spaces and dashes and upper-cases, so "12-345 678" and "12345678" index alike. */
  static String normalize(String value) {
    return value.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
  }
}
