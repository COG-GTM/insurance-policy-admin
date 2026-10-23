package com.acme.insurance.pas.policy.pii;

import com.acme.insurance.pas.pii.BlindIndex;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiCrypto;
import com.acme.insurance.pas.pii.PiiKey;
import com.acme.insurance.pas.pii.PiiKeyRing;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PiiProperties.class)
public class PiiConfiguration {
  @Bean
  PiiCrypto piiCrypto(PiiProperties properties) {
    PiiProperties.Encryption encryption = properties.encryption();
    PiiKey activeKey =
        key(
            encryption.activeKeyId(),
            encryption.activeKeyVersion(),
            encryption.activeKey(),
            "pas.pii.encryption.active-key");
    List<PiiKey> previousKeys =
        encryption.previousKeys().stream()
            .map(
                item ->
                    key(
                        item.keyId(),
                        item.version(),
                        item.key(),
                        "pas.pii.encryption.previous-keys"))
            .toList();
    SecretKey blindIndexKey = secretKey(properties.blindIndex().key(), "pas.pii.blind-index.key");
    return PiiCrypto.create(new PiiKeyRing(activeKey, previousKeys), blindIndexKey);
  }

  @Bean
  PiiCipher piiCipher(PiiCrypto crypto) {
    return crypto.cipher();
  }

  @Bean
  BlindIndex blindIndex(PiiCrypto crypto) {
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
