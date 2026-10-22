package com.acme.insurance.pas.policy.pii;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "pas.pii")
public record PiiProperties(Encryption encryption, BlindIndex blindIndex) {
  public PiiProperties {
    encryption = encryption == null ? new Encryption(null, 1, null, List.of()) : encryption;
    blindIndex = blindIndex == null ? new BlindIndex(null) : blindIndex;
  }

  public record Encryption(
      String activeKeyId,
      @DefaultValue("1") int activeKeyVersion,
      String activeKey,
      List<KeyMaterial> previousKeys) {
    public Encryption {
      previousKeys = previousKeys == null ? List.of() : List.copyOf(previousKeys);
    }
  }

  public record KeyMaterial(String keyId, int version, String key) {}

  public record BlindIndex(String key) {}
}
