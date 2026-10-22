package com.acme.insurance.pas.pii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class PiiCipher {
  private static final byte VERSION = 1;
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;

  private final PiiKeyRing keyRing;
  private final SecureRandom random;

  public PiiCipher(PiiKeyRing keyRing) {
    this(keyRing, new SecureRandom());
  }

  PiiCipher(PiiKeyRing keyRing, SecureRandom random) {
    this.keyRing = Objects.requireNonNull(keyRing);
    this.random = Objects.requireNonNull(random);
  }

  public byte[] encrypt(String plaintext, PiiContext context) {
    if (plaintext == null) {
      return null;
    }
    Objects.requireNonNull(context);
    PiiKey key = keyRing.active();
    byte[] iv = new byte[IV_LENGTH];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key.key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      cipher.updateAAD(context.additionalData());
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] id = key.keyId().getBytes(StandardCharsets.US_ASCII);
      ByteBuffer envelope =
          ByteBuffer.allocate(3 + id.length + Integer.BYTES + IV_LENGTH + encrypted.length);
      envelope.put((byte) 'P').put(VERSION).put((byte) id.length).put(id);
      envelope.putInt(key.version()).put(iv).put(encrypted);
      return envelope.array();
    } catch (GeneralSecurityException ex) {
      throw new PiiCryptoException();
    }
  }

  public String decrypt(byte[] envelope, PiiContext context) {
    if (envelope == null) {
      return null;
    }
    Objects.requireNonNull(context);
    try {
      ByteBuffer input = ByteBuffer.wrap(envelope);
      if (input.remaining() < 3 || input.get() != (byte) 'P' || input.get() != VERSION) {
        throw new PiiCryptoException();
      }
      int idLength = Byte.toUnsignedInt(input.get());
      if (idLength < 1
          || idLength > 64
          || input.remaining() < idLength + Integer.BYTES + IV_LENGTH + TAG_LENGTH_BYTES) {
        throw new PiiCryptoException();
      }
      byte[] idBytes = new byte[idLength];
      input.get(idBytes);
      String keyId = new String(idBytes, StandardCharsets.US_ASCII);
      int version = input.getInt();
      PiiKey key = keyRing.find(keyId, version);
      if (key == null) {
        throw new PiiCryptoException();
      }
      byte[] iv = new byte[IV_LENGTH];
      input.get(iv);
      byte[] encrypted = new byte[input.remaining()];
      input.get(encrypted);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key.key(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      cipher.updateAAD(context.additionalData());
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (PiiCryptoException ex) {
      throw ex;
    } catch (GeneralSecurityException | RuntimeException ex) {
      throw new PiiCryptoException();
    }
  }

  public static byte[] envelopeIv(byte[] envelope) {
    if (envelope == null || envelope.length < 3) {
      throw new PiiCryptoException();
    }
    ByteBuffer buffer = ByteBuffer.wrap(envelope);
    if (buffer.get() != (byte) 'P' || buffer.get() != VERSION) {
      throw new PiiCryptoException();
    }
    int idLength = Byte.toUnsignedInt(buffer.get());
    if (idLength < 1
        || idLength > 64
        || buffer.remaining() < idLength + Integer.BYTES + IV_LENGTH) {
      throw new PiiCryptoException();
    }
    buffer.position(buffer.position() + idLength + Integer.BYTES);
    return Arrays.copyOfRange(envelope, buffer.position(), buffer.position() + IV_LENGTH);
  }
}
