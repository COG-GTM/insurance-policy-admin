package com.acme.insurance.pas.pii;

import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;

public record PiiKey(String keyId, int version, SecretKey key) {
  private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public PiiKey {
    if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
      throw new IllegalArgumentException("Invalid PII key identifier");
    }
    if (version < 1) {
      throw new IllegalArgumentException("PII key version must be positive");
    }
    Objects.requireNonNull(key, "PII key is required");
    byte[] encoded = key.getEncoded();
    if (!"AES".equalsIgnoreCase(key.getAlgorithm()) || encoded == null || encoded.length != 32) {
      throw new IllegalArgumentException("PII encryption keys must be AES-256");
    }
  }
}
