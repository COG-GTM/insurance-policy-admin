package com.acme.insurance.pas.batch.migration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AB-529 DB2 → PostgreSQL migration settings ({@code pas.migration.*}).
 *
 * @param enabled turns on the migration, sync, reconciliation and parallel-run beans
 * @param source JDBC connection to DB2 {@code ACMEINS} (or a DB2-compatible stand-in)
 * @param chunkSize rows per read/write transaction for bulk load and delta sync
 * @param deltaOverlap how far before the stored watermark each delta re-reads (clock skew and
 *     in-flight DB2 units of work); re-applied rows are idempotent upserts
 * @param reportDir where reconciliation / parallel-run JSON and Markdown reports are written
 * @param triageRegister JSON file of classified differences ({@code parity/parallel-run/})
 * @param quirkRegister quirk register used to validate {@code APPROVED_FIX} links
 * @param legacyOutputDir legacy (mainframe) outputs, {@code <dir>/<business-date>/<file>}
 * @param sampleLimit max record-level differences detailed per table in a report
 */
@ConfigurationProperties("pas.migration")
public record MigrationProperties(
    @DefaultValue("false") boolean enabled,
    Source source,
    @DefaultValue("500") int chunkSize,
    @DefaultValue("PT10M") Duration deltaOverlap,
    @DefaultValue("/var/lib/pas-batch/migration") Path reportDir,
    @DefaultValue("parity/parallel-run/triage-register.json") Path triageRegister,
    @DefaultValue("docs/modernization/quirk-register.md") Path quirkRegister,
    @DefaultValue("/var/lib/pas-batch/legacy-outputs") Path legacyOutputDir,
    @DefaultValue("50") int sampleLimit) {

  public MigrationProperties {
    if (chunkSize < 1) {
      throw new IllegalArgumentException("pas.migration.chunk-size must be >= 1");
    }
    if (source == null) {
      source = new Source(null, null, null, "ACMEINS", null);
    }
  }

  /**
   * @param url JDBC URL, e.g. {@code jdbc:db2://host:446/DB2P}; empty disables source access
   * @param username source user (read-only grant on ACMEINS)
   * @param password from the secrets store via {@code PAS_MIGRATION_SOURCE_PASSWORD}
   * @param schema source schema, {@code ACMEINS} on DB2
   * @param driverClassName optional explicit driver (otherwise derived from the URL)
   */
  public record Source(
      String url, String username, String password, String schema, String driverClassName) {

    public Source {
      if (schema == null || schema.isBlank()) {
        schema = "ACMEINS";
      }
    }

    public boolean configured() {
      return url != null && !url.isBlank();
    }

    @Override
    public String toString() {
      return "Source[url=" + url + ", username=" + username + ", schema=" + schema + "]";
    }
  }
}
