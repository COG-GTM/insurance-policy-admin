package com.acme.insurance.pas.batch.premium;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for {@code premiumRecalculationJob} ({@code pas.batch.premium.*}).
 *
 * @param cron Spring cron for the nightly run; {@code -} disables the schedule (default until
 *     cut-over, while CA-7 PAS0050 still owns the run)
 * @param zone time zone of the cron and of the default run date (CA-7 ran at 01:00 EST)
 * @param partitions number of policy-number ranges processed in parallel
 * @param chunkSize policies per transaction (also the JDBC page size)
 * @param skipLimit per-partition limit of PREMIUMS write errors before the run fails
 * @param reportDir directory for the PREMRPT run report and its per-partition part files
 * @param writeTarget {@code pas} writes {@code pas.premiums}; {@code shadow} writes {@code
 *     pas_shadow.premiums} for the AB-529 parallel run (required while DB2 is authoritative)
 */
@ConfigurationProperties("pas.batch.premium")
public record PremiumJobProperties(
    @DefaultValue("-") String cron,
    @DefaultValue("America/New_York") String zone,
    @DefaultValue("8") int partitions,
    @DefaultValue("1000") int chunkSize,
    @DefaultValue("10000") int skipLimit,
    @DefaultValue("./reports/premium") Path reportDir,
    @DefaultValue("pas") PremiumWriteTarget writeTarget) {

  public PremiumJobProperties {
    if (partitions < 1 || chunkSize < 1 || skipLimit < 0) {
      throw new IllegalArgumentException(
          "pas.batch.premium: partitions and chunk-size must be >= 1, skip-limit >= 0");
    }
  }
}
