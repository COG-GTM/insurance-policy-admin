package com.acme.insurance.pas.persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One row of {@code pasapp.extract_delivery}: file metadata only, never record content. */
public record DeliveryReceipt(
    String feed,
    LocalDate businessDate,
    String fileName,
    long recordCount,
    long byteCount,
    String sha256,
    String destination,
    Status status,
    int attempts,
    Long jobExecutionId,
    String detail,
    LocalDateTime confirmedAt) {

  public enum Status {
    CONFIRMED,
    FAILED
  }
}
