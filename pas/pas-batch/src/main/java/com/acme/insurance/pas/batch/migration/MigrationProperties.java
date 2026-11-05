package com.acme.insurance.pas.batch.migration;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the AB-529 migration jobs. The two connections are separate from the batch job
 * repository datasource and come from the secrets manager; nothing here has a credential default.
 *
 * @param db2 the DB2 ACMEINS connection (system of record until cutover)
 * @param postgres the PostgreSQL target connection; also holds the acmeins_shadow results
 * @param bucketDigits trailing key characters per bucket: 1 means about 10 buckets, 2 about 100. A
 *     bucket is the unit compared in memory, so a larger book needs more digits
 * @param batchSize rows per JDBC batch when applying inserts, updates and deletes
 * @param hashTables tables compared record by record (AB-529: POLICIES, COVERAGES, PREMIUMS)
 * @param reportDir where the daily JSON and Markdown reconciliation reports are written
 * @param zone business zone used for the default business date
 * @param sequenceGap how far the receiving POLICY_SEQ is set above the sending high-water mark;
 *     covers numbers cached by DB2 (CACHE 20) or PostgreSQL that are not yet visible
 */
@ConfigurationProperties("pas.migration")
public record MigrationProperties(
    @DefaultValue Connection db2,
    @DefaultValue Connection postgres,
    @DefaultValue("1") int bucketDigits,
    @DefaultValue("500") int batchSize,
    @DefaultValue({"POLICIES", "COVERAGES", "PREMIUMS"}) List<MigrationTable> hashTables,
    Path reportDir,
    @DefaultValue("America/New_York") ZoneId zone,
    @DefaultValue("100") long sequenceGap) {

  public MigrationProperties {
    hashTables = List.copyOf(hashTables);
    if (bucketDigits < 0 || bucketDigits > 4) {
      throw new IllegalArgumentException("pas.migration.bucket-digits must be 0..4");
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("pas.migration.batch-size must be positive");
    }
  }

  /** A JDBC connection; the password comes from the secrets manager. */
  public record Connection(String url, String username, String password) {

    public boolean configured() {
      return url != null && !url.isBlank();
    }
  }
}
