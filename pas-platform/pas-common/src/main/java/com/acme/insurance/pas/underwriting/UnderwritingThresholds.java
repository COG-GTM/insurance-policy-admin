package com.acme.insurance.pas.underwriting;

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
