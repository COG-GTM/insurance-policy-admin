package com.acme.insurance.pas.batch.extract;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The three files that replace the outputs of DAILY-EXTRACT and MONTHLY-EXPOSURE. Remote names are
 * the ones the consumers import today; staging names carry the business date so a re-run never
 * collides with an earlier run (quirk Q-33).
 */
public enum ExtractFeed {
  CLAIMS_POLICY(
      Consumer.CLAIMS, FeedLayouts.CLAIMS_POLICY, "PAS.CLAIMS.POLICY", "policy_extract.dat"),
  CLAIMS_COVERAGE(
      Consumer.CLAIMS, FeedLayouts.CLAIMS_COVERAGE, "PAS.CLAIMS.COVERAGE", "coverage_extract.dat"),
  ACTUARIAL_EXPOSURE(
      Consumer.ACTUARIAL,
      FeedLayouts.ACTUARIAL_EXPOSURE,
      "PAS.ACTUARIAL.EXPOSURE",
      "exposure_monthly.csv");

  /** Downstream system that receives the file; selects the SFTP endpoint. */
  public enum Consumer {
    CLAIMS,
    ACTUARIAL
  }

  private static final DateTimeFormatter STAMP = DateTimeFormatter.BASIC_ISO_DATE;

  private final Consumer consumer;
  private final FeedLayout layout;
  private final String stagingPrefix;
  private final String remoteName;

  ExtractFeed(Consumer consumer, FeedLayout layout, String stagingPrefix, String remoteName) {
    this.consumer = consumer;
    this.layout = layout;
    this.stagingPrefix = stagingPrefix;
    this.remoteName = remoteName;
  }

  public Consumer consumer() {
    return consumer;
  }

  public FeedLayout layout() {
    return layout;
  }

  public String remoteName() {
    return remoteName;
  }

  public String feedId() {
    return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }

  public String stagingName(LocalDate businessDate) {
    return stagingPrefix + ".D" + businessDate.format(STAMP);
  }
}
