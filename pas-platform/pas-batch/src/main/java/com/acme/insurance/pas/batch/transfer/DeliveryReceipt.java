package com.acme.insurance.pas.batch.transfer;

/** Confirmation that a file arrived intact: remote path, verified size and SHA-256. */
public record DeliveryReceipt(String destination, String remotePath, long bytes, String sha256) {}
