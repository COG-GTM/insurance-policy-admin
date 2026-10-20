package com.acme.insurance.pas.common.pii;

/** Encryption/decryption failure. Messages never include plaintext or key material. */
public class PiiCryptoException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PiiCryptoException(String message) {
    super(message);
  }

  public PiiCryptoException(String message, Throwable cause) {
    super(message, cause);
  }
}
