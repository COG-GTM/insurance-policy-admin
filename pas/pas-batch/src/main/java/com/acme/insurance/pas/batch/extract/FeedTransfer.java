package com.acme.insurance.pas.batch.extract;

import java.nio.file.Path;

/** Secure delivery of one staged file plus its checksum manifest. */
public interface FeedTransfer {

  /**
   * Uploads {@code file} and {@code manifest} and returns only once the remote copies are in place
   * and read back with the expected SHA-256; throws {@link DeliveryException} otherwise.
   */
  void deliver(SftpDestination destination, StagedFile file, Path manifest);

  /** Delivery failed; the message never contains credentials or record content. */
  class DeliveryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DeliveryException(String message) {
      super(message);
    }

    public DeliveryException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
