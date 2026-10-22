package com.acme.insurance.pas.pii;

public final class PiiCryptoException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PiiCryptoException() {
    super("PII encryption envelope could not be processed");
  }

  public PiiCryptoException(Throwable cause) {
    super("PII encryption envelope could not be processed", cause);
  }
}
