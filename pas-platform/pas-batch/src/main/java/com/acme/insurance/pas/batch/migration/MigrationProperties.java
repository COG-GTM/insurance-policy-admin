package com.acme.insurance.pas.batch.migration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.migration")
public record MigrationProperties(
    boolean enabled,
    Source source,
    Bulk bulk,
    DeltaSync deltaSync,
    ReverseSync reverseSync,
    Recon recon,
    ParallelRun parallelRun) {
  public MigrationProperties {
    source =
        source == null
            ? new Source(null, null, null, "ACMEINS", true, null, "America/New_York", 500)
            : source;
    bulk = bulk == null ? new Bulk(8, 500) : bulk;
    deltaSync =
        deltaSync == null
            ? new DeltaSync(
                Duration.ofMinutes(5), false, Duration.ofMinutes(15), false, Duration.ofMinutes(1))
            : deltaSync;
    reverseSync = reverseSync == null ? new ReverseSync(false, 100, null, null) : reverseSync;
    recon =
        recon == null
            ? new Recon(false, "0 0 3 * * *", "/var/lib/pas-batch/reports/recon", null)
            : recon;
    parallelRun =
        parallelRun == null
            ? new ParallelRun("/var/lib/pas-batch/legacy-extracts", "/var/lib/pas-batch/extracts")
            : parallelRun;
  }

  public record Source(
      String url,
      String username,
      String password,
      String schema,
      boolean ssl,
      String sslTrustStoreLocation,
      String zone,
      int fetchSize) {}

  public record Bulk(int gridSize, int chunkSize) {}

  public record DeltaSync(
      Duration overlap,
      boolean paused,
      Duration lagAlertThreshold,
      boolean schedulerEnabled,
      Duration fixedDelay) {}

  public record ReverseSync(boolean enabled, int maxPasses, String username, String password) {}

  public record Recon(
      boolean schedulerEnabled, String schedule, String reportDir, String fingerprintKey) {}

  public record ParallelRun(String legacyExtractDir, String extractWorkDir) {}
}
