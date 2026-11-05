package com.acme.insurance.pas.batch.extract;

import com.acme.insurance.pas.persistence.DeliveryReceipt;
import com.acme.insurance.pas.persistence.ExtractJdbcRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers one staged file: writes the SHA-256 manifest, transfers both with retries and backoff,
 * records a receipt, and purges the staged copies once the delivery is confirmed. A file that still
 * fails after the last attempt gets a FAILED receipt and fails the step, which fails the job and
 * raises the alert.
 */
public class FeedDelivery {

  private static final Logger log = LoggerFactory.getLogger(FeedDelivery.class);

  /** Waits between attempts; replaced in tests. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final FeedTransfer transfer;
  private final ExtractJdbcRepository receipts;
  private final ExtractProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;
  private final Sleeper sleeper;

  public FeedDelivery(
      FeedTransfer transfer,
      ExtractJdbcRepository receipts,
      ExtractProperties properties,
      MeterRegistry meters,
      Clock clock,
      Sleeper sleeper) {
    this.transfer = transfer;
    this.receipts = receipts;
    this.properties = properties;
    this.meters = meters;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  public void deliver(StagedFile file, Long jobExecutionId) {
    SftpDestination destination = properties.destination(file.feed().consumer());
    String where = destination.describe(file.feed().remoteName());
    Path manifest = writeManifest(file);
    int maxAttempts = Math.max(1, properties.retry().maxAttempts());
    RuntimeException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        transfer.deliver(destination, file, manifest);
        receipts.recordDelivery(
            receipt(
                file,
                where,
                DeliveryReceipt.Status.CONFIRMED,
                attempt,
                jobExecutionId,
                null,
                LocalDateTime.now(clock)));
        count(file, "confirmed");
        log.info(
            "extract delivery confirmed feed={} businessDate={} records={} bytes={} sha256={}"
                + " destination={} attempt={}",
            file.feed().feedId(),
            file.businessDate(),
            file.recordCount(),
            file.byteCount(),
            file.sha256(),
            where,
            attempt);
        if (properties.purgeAfterDelivery()) {
          purge(file.path(), manifest);
        }
        return;
      } catch (RuntimeException e) {
        last = e;
        log.warn(
            "extract delivery attempt failed feed={} businessDate={} destination={} attempt={}/{}"
                + " reason={}",
            file.feed().feedId(),
            file.businessDate(),
            where,
            attempt,
            maxAttempts,
            e.getMessage());
        if (attempt < maxAttempts) {
          backoff(properties.retry().backoff().multipliedBy(attempt));
        }
      }
    }
    receipts.recordDelivery(
        receipt(
            file,
            where,
            DeliveryReceipt.Status.FAILED,
            maxAttempts,
            jobExecutionId,
            last.getMessage(),
            null));
    count(file, "failed");
    throw new FeedTransfer.DeliveryException(
        file.feed().feedId() + " not delivered after " + maxAttempts + " attempts", last);
  }

  private DeliveryReceipt receipt(
      StagedFile file,
      String where,
      DeliveryReceipt.Status status,
      int attempts,
      Long jobExecutionId,
      String detail,
      LocalDateTime confirmedAt) {
    return new DeliveryReceipt(
        file.feed().feedId(),
        file.businessDate(),
        file.feed().remoteName(),
        file.recordCount(),
        file.byteCount(),
        file.sha256(),
        where,
        status,
        attempts,
        jobExecutionId,
        detail,
        confirmedAt);
  }

  private void count(StagedFile file, String outcome) {
    meters
        .counter("pas.extract.deliveries", "feed", file.feed().feedId(), "outcome", outcome)
        .increment();
  }

  private void backoff(Duration duration) {
    try {
      sleeper.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FeedTransfer.DeliveryException("interrupted while waiting to retry", e);
    }
  }

  private static Path writeManifest(StagedFile file) {
    Path manifest = file.path().resolveSibling(file.path().getFileName() + ".sha256");
    try {
      Files.deleteIfExists(manifest);
      FeedFileWriter.createOwnerOnly(manifest, false);
      Files.writeString(manifest, file.manifestLine(), StandardCharsets.US_ASCII);
      return manifest;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write manifest for " + file.feed().feedId(), e);
    }
  }

  private static void purge(Path... files) {
    for (Path f : files) {
      try {
        Files.deleteIfExists(f);
      } catch (IOException e) {
        log.warn("cannot purge staged file {}: {}", f.getFileName(), e.getMessage());
      }
    }
  }
}
