package com.acme.insurance.pas.policy.pii;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PII key material, bound from {@code pas.pii.*}. Keys are Base64-encoded 256-bit values supplied
 * through the environment ({@code PAS_PII_ENCRYPTION_KEY}, {@code PAS_PII_BLIND_INDEX_KEY}); there
 * are no defaults. {@link #toString()} never prints key material.
 */
@ConfigurationProperties("pas.pii")
public record PiiProperties(String encryptionKey, String blindIndexKey, String keyId) {

  @Override
  public String toString() {
    return "PiiProperties[keyId=" + keyId + ", keys=REDACTED]";
  }
}
