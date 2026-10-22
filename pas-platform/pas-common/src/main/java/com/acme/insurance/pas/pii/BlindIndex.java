package com.acme.insurance.pas.pii;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

public final class BlindIndex {
  private final SecretKey key;

  public BlindIndex(SecretKey key) {
    this.key = Objects.requireNonNull(key);
  }

  public byte[] of(String value) {
    if (value == null) {
      return null;
    }
    String normalized =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(" ", "")
            .replace("-", "")
            .toUpperCase(Locale.ROOT);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException("Blind index algorithm unavailable", ex);
    }
  }
}
