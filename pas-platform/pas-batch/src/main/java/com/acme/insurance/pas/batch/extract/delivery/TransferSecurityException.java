package com.acme.insurance.pas.batch.extract.delivery;

import java.io.IOException;

public final class TransferSecurityException extends IOException {
  public TransferSecurityException(String message, Throwable cause) {
    super(message, cause);
  }
}
