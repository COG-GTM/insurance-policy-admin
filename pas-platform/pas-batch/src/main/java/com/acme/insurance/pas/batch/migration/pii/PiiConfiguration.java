package com.acme.insurance.pas.batch.migration.pii;

import com.acme.insurance.pas.pii.BlindIndex;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiCrypto;
import com.acme.insurance.pas.pii.PiiKey;
import com.acme.insurance.pas.pii.PiiKeyRing;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PiiProperties.class)
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
public class PiiConfiguration {
  @Bean
  PiiCrypto migrationPiiCrypto(PiiProperties properties) {
    PiiProperties.Encryption encryption = properties.encryption();
    PiiKey activeKey =
        key(
            encryption.activeKeyId(),
            encryption.activeKeyVersion(),
            encryption.activeKey(),
            "pas.pii.encryption.active-key");
    SecretKey blindIndexKey = secretKey(properties.blindIndex().key(), "pas.pii.blind-index.key");
    return PiiCrypto.create(new PiiKeyRing(activeKey, List.of()), blindIndexKey);
  }

  @Bean
  PiiCipher migrationPiiCipher(PiiCrypto crypto) {
    return crypto.cipher();
  }

  @Bean
  BlindIndex migrationBlindIndex(PiiCrypto crypto) {
    return crypto.blindIndex();
  }

  private static PiiKey key(String keyId, int version, String encoded, String property) {
    return new PiiKey(keyId, version, secretKey(encoded, property));
  }

  private static SecretKey secretKey(String encoded, String property) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalStateException(property + " must be configured");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(property + " must be base64-encoded", exception);
    }
    if (bytes.length != 32) {
      throw new IllegalStateException(property + " must decode to 32 bytes");
    }
    return new SecretKeySpec(bytes, "AES");
  }
}
