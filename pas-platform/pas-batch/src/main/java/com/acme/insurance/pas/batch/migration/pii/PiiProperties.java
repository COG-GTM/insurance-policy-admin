package com.acme.insurance.pas.batch.migration.pii;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.pii")
public record PiiProperties(Encryption encryption, BlindIndex blindIndex) {
  public PiiProperties {
    encryption = encryption == null ? new Encryption(null, 1, null) : encryption;
    blindIndex = blindIndex == null ? new BlindIndex(null) : blindIndex;
  }

  public record Encryption(String activeKeyId, int activeKeyVersion, String activeKey) {}

  public record BlindIndex(String key) {}
}
