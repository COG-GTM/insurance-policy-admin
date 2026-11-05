package com.acme.insurance.pas.batch.premium;

import java.nio.file.Path;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the premium recalculation job ({@code pas.premium.*}).
 *
 * @param reportDir where the run report (PREMRPT replacement) is written; required
 * @param zone business-date zone of the scheduled run (PAS0050 ran at 01:00 Eastern)
 * @param partitions policy-number ranges rated in parallel; the connection pool needs one
 *     connection per partition plus the job repository's
 * @param chunkSize policies per transaction (commit interval)
 * @param skipLimit per-partition limit of policies whose PREMIUMS write the database rejects; above
 *     it the partition fails and the run alerts
 */
@ConfigurationProperties("pas.premium")
public record PremiumProperties(
    Path reportDir,
    @DefaultValue("America/New_York") ZoneId zone,
    @DefaultValue("8") int partitions,
    @DefaultValue("500") int chunkSize,
    @DefaultValue("1000") int skipLimit) {

  public PremiumProperties {
    if (partitions < 1) {
      throw new IllegalArgumentException("pas.premium.partitions must be at least 1");
    }
    if (chunkSize < 1) {
      throw new IllegalArgumentException("pas.premium.chunk-size must be at least 1");
    }
    if (skipLimit < 0) {
      throw new IllegalArgumentException("pas.premium.skip-limit must not be negative");
    }
  }
}
