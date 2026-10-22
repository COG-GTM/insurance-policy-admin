package com.acme.insurance.pas.pii;

import java.security.MessageDigest;
import java.util.Objects;
import javax.crypto.SecretKey;

public record PiiCrypto(PiiCipher cipher, BlindIndex blindIndex) {
  public static PiiCrypto create(PiiKeyRing encryptionKeyRing, SecretKey blindIndexKey) {
    Objects.requireNonNull(encryptionKeyRing);
    Objects.requireNonNull(blindIndexKey);
    byte[] blindEncoded = blindIndexKey.getEncoded();
    if (blindEncoded == null) {
      throw new IllegalArgumentException("Blind-index key must be exportable");
    }
    for (PiiKey encryptionKey : encryptionKeyRing.keys()) {
      byte[] encryptionEncoded = encryptionKey.key().getEncoded();
      if (encryptionEncoded != null && MessageDigest.isEqual(encryptionEncoded, blindEncoded)) {
        throw new IllegalArgumentException("PII encryption and blind-index keys must be distinct");
      }
    }
    return new PiiCrypto(new PiiCipher(encryptionKeyRing), new BlindIndex(blindIndexKey));
  }
}
