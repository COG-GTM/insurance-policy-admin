package com.acme.insurance.pas.policy.pii;

import com.acme.insurance.pas.common.pii.BlindIndexer;
import com.acme.insurance.pas.common.pii.PiiCipher;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the PII cipher and blind indexer from {@link PiiProperties}; fails fast on bad keys. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PiiProperties.class)
public class PiiConfiguration {

  @Bean
  PiiCipher piiCipher(PiiProperties properties) {
    byte[] key = decode("pas.pii.encryption-key", properties.encryptionKey());
    if (MessageDigest.isEqual(key, decode("pas.pii.blind-index-key", properties.blindIndexKey()))) {
      throw new IllegalStateException("pas.pii.encryption-key and blind-index-key must differ");
    }
    String keyId = properties.keyId() == null ? "" : properties.keyId().strip();
    return new PiiCipher(key, keyId.isEmpty() ? "k1" : keyId);
  }

  @Bean
  BlindIndexer blindIndexer(PiiProperties properties) {
    return new BlindIndexer(decode("pas.pii.blind-index-key", properties.blindIndexKey()));
  }

  private static byte[] decode(String property, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(property + " is not set");
    }
    try {
      byte[] key = Base64.getDecoder().decode(value.strip());
      if (key.length != 32) {
        throw new IllegalStateException(property + " must be a Base64-encoded 256-bit key");
      }
      return key;
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(property + " is not valid Base64", e);
    }
  }
}
