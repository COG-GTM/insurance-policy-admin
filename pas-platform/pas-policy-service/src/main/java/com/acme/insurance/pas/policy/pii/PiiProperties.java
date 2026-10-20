package com.acme.insurance.pas.policy.pii;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PII key material, bound from {@code pas.pii.*}. Keys are Base64-encoded 256-bit values supplied
 * through the environment ({@code PAS_PII_ENCRYPTION_KEY}, {@code PAS_PII_BLIND_INDEX_KEY}); there
 * are no defaults. {@code pas.pii.retired-keys.<keyId>} holds decrypt-only keys during rotation.
 * {@link #toString()} never prints key material.
 */
@ConfigurationProperties("pas.pii")
public record PiiProperties(
    String encryptionKey, String blindIndexKey, String keyId, Map<String, String> retiredKeys) {

  public PiiProperties {
    retiredKeys = retiredKeys == null ? Map.of() : Map.copyOf(retiredKeys);
  }

  @Override
  public String toString() {
    return "PiiProperties[keyId=" + keyId + ", keys=REDACTED]";
  }
}
