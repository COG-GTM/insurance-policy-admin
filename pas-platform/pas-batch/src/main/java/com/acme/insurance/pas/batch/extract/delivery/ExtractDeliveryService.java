package com.acme.insurance.pas.batch.extract.delivery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExtractDeliveryService {
  private final RemoteFileStore remoteFileStore;
  private final SftpProperties properties;
  private final ExtractRetryProperties retryProperties;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;
  private final Sleeper sleeper;
  private final TransactionTemplate deliveryRecordTransactions;

  @Autowired
  public ExtractDeliveryService(
      RemoteFileStore remoteFileStore,
      SftpProperties properties,
      ExtractRetryProperties retryProperties,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager) {
    this(
        remoteFileStore,
        properties,
        retryProperties,
        jdbcTemplate,
        Clock.systemUTC(),
        Thread::sleep,
        newDeliveryRecordTransactions(transactionManager));
  }

  ExtractDeliveryService(
      RemoteFileStore remoteFileStore,
      SftpProperties properties,
      ExtractRetryProperties retryProperties,
      JdbcTemplate jdbcTemplate,
      Clock clock,
      Sleeper sleeper) {
    this(remoteFileStore, properties, retryProperties, jdbcTemplate, clock, sleeper, null);
  }

  private ExtractDeliveryService(
      RemoteFileStore remoteFileStore,
      SftpProperties properties,
      ExtractRetryProperties retryProperties,
      JdbcTemplate jdbcTemplate,
      Clock clock,
      Sleeper sleeper,
      TransactionTemplate deliveryRecordTransactions) {
    this.remoteFileStore = remoteFileStore;
    this.properties = properties;
    this.retryProperties = retryProperties;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
    this.sleeper = sleeper;
    this.deliveryRecordTransactions = deliveryRecordTransactions;
  }

  public int deliver(
      String targetName,
      String jobName,
      long jobExecutionId,
      LocalDate businessDate,
      List<DeliveryFile> files)
      throws IOException {
    SftpProperties.Target target = properties.getTargets().get(targetName);
    validateTarget(target);
    List<PreparedFile> prepared = files.stream().map(this::prepare).toList();
    List<String> donePaths =
        prepared.stream()
            .map(file -> remotePath(target.remoteDir(), file.remoteName()) + ".done")
            .toList();
    Set<String> temporaryPaths = new LinkedHashSet<>();
    int attempts = 0;
    Instant started = clock.instant();
    IOException finalFailure = null;
    Duration delay = retryProperties.initialBackoff();
    for (int attempt = 1; attempt <= retryProperties.maxAttempts(); attempt++) {
      attempts = attempt;
      try (RemoteFileStore.Session session = remoteFileStore.connect(target)) {
        try {
          deleteAll(session, donePaths);
          for (PreparedFile file : prepared) {
            transferFile(session, target.remoteDir(), file, jobExecutionId, temporaryPaths);
          }
          for (PreparedFile file : prepared) {
            publishDoneMarker(
                session, target.remoteDir(), file, jobExecutionId, businessDate, temporaryPaths);
          }
        } catch (IOException | RuntimeException failure) {
          deleteBestEffort(session, donePaths);
          throw failure;
        }
        Instant completed = clock.instant();
        for (PreparedFile file : prepared) {
          upsert(
              jobName,
              jobExecutionId,
              businessDate,
              targetName,
              file,
              "DELIVERED",
              attempts,
              null,
              started,
              completed);
        }
        return attempts;
      } catch (TransferSecurityException exception) {
        finalFailure = exception;
        break;
      } catch (IOException exception) {
        finalFailure = exception;
        if (attempt < retryProperties.maxAttempts()) {
          try {
            sleeper.sleep(delay);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finalFailure = new IOException("SFTP retry interrupted", interrupted);
            break;
          }
          delay = delay.multipliedBy(retryProperties.multiplier());
          if (delay.compareTo(retryProperties.maxBackoff()) > 0) {
            delay = retryProperties.maxBackoff();
          }
        }
      }
    }
    if (!(finalFailure instanceof TransferSecurityException)) {
      cleanupRemoteArtifacts(target, donePaths, temporaryPaths);
    }
    Instant completed = clock.instant();
    String failureType =
        finalFailure == null
            ? IOException.class.getSimpleName()
            : finalFailure.getClass().getSimpleName();
    for (PreparedFile file : prepared) {
      upsert(
          jobName,
          jobExecutionId,
          businessDate,
          targetName,
          file,
          "FAILED",
          attempts,
          failureType,
          started,
          completed);
    }
    throw finalFailure == null ? new IOException("SFTP delivery failed") : finalFailure;
  }

  private void transferFile(
      RemoteFileStore.Session session,
      String remoteDir,
      PreparedFile file,
      long jobExecutionId,
      Set<String> temporaryPaths)
      throws IOException {
    String remotePath = remotePath(remoteDir, file.remoteName());
    String temporary = remotePath + "." + jobExecutionId + ".tmp";
    temporaryPaths.add(temporary);
    session.delete(temporary);
    session.upload(file.file().path(), temporary);
    if (session.size(temporary) != file.byteCount()) {
      throw new IOException("Remote extract size verification failed");
    }
    session.rename(temporary, remotePath);
    String checksumPath = remotePath + ".sha256";
    String checksumTemporary = checksumPath + "." + jobExecutionId + ".tmp";
    temporaryPaths.add(checksumTemporary);
    session.delete(checksumTemporary);
    session.write(
        (file.sha256() + "  " + file.remoteName() + "\n").getBytes(StandardCharsets.US_ASCII),
        checksumTemporary);
    session.rename(checksumTemporary, checksumPath);
  }

  private void publishDoneMarker(
      RemoteFileStore.Session session,
      String remoteDir,
      PreparedFile file,
      long jobExecutionId,
      LocalDate businessDate,
      Set<String> temporaryPaths)
      throws IOException {
    String remotePath = remotePath(remoteDir, file.remoteName());
    String donePath = remotePath + ".done";
    String marker =
        "layout="
            + file.file().layoutVersion()
            + "\nrecords="
            + file.file().recordCount()
            + "\nbytes="
            + file.byteCount()
            + "\nsha256="
            + file.sha256()
            + "\nbusinessDate="
            + businessDate
            + "\njobExecutionId="
            + jobExecutionId
            + "\n";
    String markerTemporary = donePath + "." + jobExecutionId + ".tmp";
    temporaryPaths.add(markerTemporary);
    session.delete(markerTemporary);
    session.write(marker.getBytes(StandardCharsets.US_ASCII), markerTemporary);
    session.rename(markerTemporary, donePath);
  }

  private void cleanupRemoteArtifacts(
      SftpProperties.Target target, List<String> donePaths, Set<String> temporaryPaths) {
    Set<String> paths = new LinkedHashSet<>(donePaths);
    paths.addAll(temporaryPaths);
    try (RemoteFileStore.Session session = remoteFileStore.connect(target)) {
      deleteBestEffort(session, paths);
    } catch (IOException | RuntimeException ignored) {
      // Preserve the original delivery failure if cleanup cannot connect.
    }
  }

  private static void deleteAll(RemoteFileStore.Session session, List<String> paths)
      throws IOException {
    IOException firstFailure = null;
    for (String path : paths) {
      try {
        session.delete(path);
      } catch (IOException exception) {
        if (firstFailure == null) {
          firstFailure = exception;
        }
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }

  private static void deleteBestEffort(RemoteFileStore.Session session, Iterable<String> paths) {
    for (String path : paths) {
      try {
        session.delete(path);
      } catch (IOException | RuntimeException ignored) {
        // Continue best-effort cleanup without masking the delivery failure.
      }
    }
  }

  private PreparedFile prepare(DeliveryFile file) {
    try {
      byte[] content = Files.readAllBytes(file.path());
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
      return new PreparedFile(
          file, file.remoteName(), content.length, HexFormat.of().formatHex(digest));
    } catch (IOException exception) {
      throw new IllegalStateException("Extract output file is unavailable", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void upsert(
      String jobName,
      long jobExecutionId,
      LocalDate businessDate,
      String target,
      PreparedFile file,
      String status,
      int attempts,
      String failureType,
      Instant startedAt,
      Instant completedAt) {
    String path = remotePath(properties.getTargets().get(target).remoteDir(), file.remoteName());
    Runnable update =
        () ->
            jdbcTemplate.update(
                """
                INSERT INTO pas_batch.extract_delivery
                  (job_name, job_execution_id, business_date, target, remote_path, layout_version,
                   record_count, byte_count, sha256, status, attempts, failure_type, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_execution_id, remote_path) DO UPDATE SET
                  job_name = EXCLUDED.job_name,
                  business_date = EXCLUDED.business_date,
                  target = EXCLUDED.target,
                  layout_version = EXCLUDED.layout_version,
                  record_count = EXCLUDED.record_count,
                  byte_count = EXCLUDED.byte_count,
                  sha256 = EXCLUDED.sha256,
                  status = EXCLUDED.status,
                  attempts = EXCLUDED.attempts,
                  failure_type = EXCLUDED.failure_type,
                  started_at = EXCLUDED.started_at,
                  completed_at = EXCLUDED.completed_at
                """,
                jobName,
                jobExecutionId,
                java.sql.Date.valueOf(businessDate),
                target,
                path,
                file.file().layoutVersion(),
                file.file().recordCount(),
                file.byteCount(),
                file.sha256(),
                status,
                attempts,
                failureType,
                Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt));
    if (deliveryRecordTransactions == null) {
      update.run();
    } else {
      deliveryRecordTransactions.executeWithoutResult(transaction -> update.run());
    }
  }

  private static TransactionTemplate newDeliveryRecordTransactions(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactions;
  }

  private static void validateTarget(SftpProperties.Target target)
      throws TransferSecurityException {
    if (target == null
        || blank(target.host())
        || target.port() < 1
        || blank(target.username())
        || blank(target.privateKeyFile())
        || blank(target.knownHostsFile())
        || blank(target.remoteDir())) {
      throw new TransferSecurityException("SFTP target configuration is incomplete", null);
    }
  }

  private static String remotePath(String directory, String name) {
    return Path.of(directory, name).toString().replace('\\', '/');
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record DeliveryFile(
      Path path, String remoteName, String layoutVersion, long recordCount) {}

  private record PreparedFile(
      DeliveryFile file, String remoteName, long byteCount, String sha256) {}

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}
