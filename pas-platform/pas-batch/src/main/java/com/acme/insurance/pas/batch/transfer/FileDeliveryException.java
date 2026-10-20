package com.acme.insurance.pas.batch.transfer;

/** Delivery to a destination failed after all retry attempts. */
public class FileDeliveryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public FileDeliveryException(String message, Throwable cause) {
    super(message, cause);
  }
}
