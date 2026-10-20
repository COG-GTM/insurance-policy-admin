package com.acme.insurance.pas.batch.transfer;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code pas.transfer.enabled=false} (local dev, tests, pre-cut-over): files stay on disk. */
public class LocalOnlyFileTransfer implements SecureFileTransfer {

  private static final Logger LOG = LoggerFactory.getLogger(LocalOnlyFileTransfer.class);

  @Override
  public List<DeliveryReceipt> deliver(String destination, List<DeliveryFile> files) {
    LOG.warn(
        "transfer disabled (pas.transfer.enabled=false); {} file(s) for {} kept locally: {}",
        files.size(),
        destination,
        files.stream().map(DeliveryFile::localPath).toList());
    return List.of();
  }
}
