package com.acme.insurance.pas.common.pii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM field encryption. Ciphertext format (Base64 of): {@code version(1) | keyId-length(1)
 * | keyId | iv(12) | ciphertext+tag(16)}. The key id is bound as additional authenticated data so a
 * value cannot be decrypted under a different key id, and enables key rotation.
 */
public final class PiiCipher {

  static final byte FORMAT_VERSION = 1;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private final SecretKey key;
  private final String keyId;
  private final byte[] keyIdBytes;
  private final SecureRandom random;

  public PiiCipher(byte[] key256, String keyId) {
    this(key256, keyId, new SecureRandom());
  }

  PiiCipher(byte[] key256, String keyId, SecureRandom random) {
    Objects.requireNonNull(key256, "key");
    if (key256.length != 32) {
      throw new IllegalArgumentException("PII encryption key must be 256 bits (32 bytes)");
    }
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.keyIdBytes = keyId.getBytes(StandardCharsets.US_ASCII);
    if (keyIdBytes.length == 0 || keyIdBytes.length > 32) {
      throw new IllegalArgumentException("keyId must be 1-32 ASCII characters");
    }
    this.key = new SecretKeySpec(key256.clone(), "AES");
    this.random = random;
  }

  public String keyId() {
    return keyId;
  }

  public String encrypt(String plaintext) {
    if (plaintext == null) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(keyIdBytes);
      byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      ByteBuffer out = ByteBuffer.allocate(2 + keyIdBytes.length + IV_BYTES + sealed.length);
      out.put(FORMAT_VERSION).put((byte) keyIdBytes.length).put(keyIdBytes).put(iv).put(sealed);
      return Base64.getEncoder().encodeToString(out.array());
    } catch (GeneralSecurityException e) {
      throw new PiiCryptoException("PII encryption failed", e);
    }
  }

  public String decrypt(String encoded) {
    if (encoded == null) {
      return null;
    }
    try {
      ByteBuffer in = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
      if (in.get() != FORMAT_VERSION) {
        throw new PiiCryptoException("unsupported PII ciphertext version");
      }
      byte[] storedKeyId = new byte[in.get() & 0xFF];
      in.get(storedKeyId);
      if (!java.util.Arrays.equals(storedKeyId, keyIdBytes)) {
        throw new PiiCryptoException("PII ciphertext was written under a different key id");
      }
      byte[] iv = new byte[IV_BYTES];
      in.get(iv);
      byte[] sealed = new byte[in.remaining()];
      in.get(sealed);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(keyIdBytes);
      return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | RuntimeException e) {
      if (e instanceof PiiCryptoException pce) {
        throw pce;
      }
      throw new PiiCryptoException("PII decryption failed", e);
    }
  }
}
