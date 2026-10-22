package com.acme.insurance.pas.underwriting;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.underwriting")
public record UnderwritingThresholds(
    int seniorReferral,
    int managerReferral,
    int autoDecline,
    int creditCeiling,
    int noScoreBase,
    int substandardAdjustment,
    int preferredAdjustment,
    int firstClaimThreshold,
    int firstClaimAdjustment,
    int secondClaimThreshold,
    int secondClaimAdjustment,
    int lossRatioThreshold,
    int lossRatioAdjustment,
    long firstLimitThreshold,
    int firstLimitAdjustment,
    long secondLimitThreshold,
    int secondLimitAdjustment,
    long accumulationThreshold,
    int accumulationAdjustment) {

  public UnderwritingThresholds {
    if (!(seniorReferral < managerReferral && managerReferral < autoDecline)) {
      throw new IllegalArgumentException("Underwriting score bands must be strictly ascending");
    }
    if (!(firstClaimThreshold < secondClaimThreshold)) {
      throw new IllegalArgumentException("Claim thresholds must be strictly ascending");
    }
    if (!(firstLimitThreshold < secondLimitThreshold)) {
      throw new IllegalArgumentException("Limit thresholds must be strictly ascending");
    }
  }

  public static UnderwritingThresholds legacyDefaults() {
    return new UnderwritingThresholds(
        300,
        600,
        800,
        900,
        400,
        200,
        -100,
        3,
        150,
        5,
        200,
        80,
        200,
        5_000_000L,
        100,
        10_000_000L,
        200,
        500_000_000L,
        300);
  }
}
