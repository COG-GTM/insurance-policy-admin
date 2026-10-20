package com.acme.insurance.pas.batch.transfer;

import java.util.List;

/**
 * Encrypted, verified delivery of extract files to a named destination ({@code claims}, {@code
 * actuarial}). There is deliberately no clear-text implementation.
 */
public interface SecureFileTransfer {

  /**
   * Delivers all files or throws {@link FileDeliveryException}. Returns one receipt per file; an
   * empty list means delivery is disabled in this environment (files stay local).
   */
  List<DeliveryReceipt> deliver(String destination, List<DeliveryFile> files);
}
