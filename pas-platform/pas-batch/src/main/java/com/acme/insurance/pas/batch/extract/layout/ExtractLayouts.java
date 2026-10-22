package com.acme.insurance.pas.batch.extract.layout;

import com.acme.insurance.pas.copybook.RecordLayout;
import java.util.List;
import java.util.Map;

public final class ExtractLayouts {
  public static final ExtractLayout DAILY_POLICY =
      ExtractLayout.fixed(
          "daily-policy",
          1,
          200,
          RecordLayout.builder("DAILY-POLICY")
              .x("POLICY_NUMBER", 12)
              .x("POLICY_TYPE", 3)
              .x("POLICY_STATUS", 2)
              .date("EFFECTIVE_DATE")
              .date("EXPIRY_DATE")
              .x("POLICYHOLDER_ID", 10)
              .x("AGENT_CODE", 6)
              .x("BRANCH_CODE", 4)
              .signedDisplay("TOTAL_PREMIUM", 11, 2)
              .signedDisplay("DEDUCTIBLE", 9, 2)
              .signedDisplay("COVERAGE_LIMIT", 13, 2)
              .x("UW_STATUS", 2)
              .signedDisplay("RISK_SCORE", 5, 0)
              .filler(107)
              .build(200));

  public static final ExtractLayout DAILY_COVERAGE =
      ExtractLayout.fixed(
          "daily-coverage",
          1,
          150,
          RecordLayout.builder("DAILY-COVERAGE")
              .x("POLICY_NUMBER", 12)
              .signedDisplay("SEQUENCE_NUM", 5, 0)
              .x("COVERAGE_TYPE", 4)
              .x("DESCRIPTION", 40)
              .signedDisplay("COVERAGE_LIMIT", 13, 2)
              .signedDisplay("DEDUCTIBLE", 9, 2)
              .signedDisplay("PREMIUM", 11, 2)
              .date("EFFECTIVE_DATE")
              .date("EXPIRY_DATE")
              .x("STATUS", 2)
              .filler(38)
              .build(150));

  public static final ExtractLayout MONTHLY_EXPOSURE =
      ExtractLayout.csv("monthly-exposure", 1, 300);

  private static final Map<String, ExtractLayout> BY_ID =
      Map.of(
          DAILY_POLICY.id(), DAILY_POLICY,
          DAILY_COVERAGE.id(), DAILY_COVERAGE,
          MONTHLY_EXPOSURE.id(), MONTHLY_EXPOSURE);
  private static final Map<String, ExtractLayout> BY_FILE =
      Map.of(
          "policy_extract.dat", DAILY_POLICY,
          "coverage_extract.dat", DAILY_COVERAGE,
          "exposure_monthly.csv", MONTHLY_EXPOSURE);

  private ExtractLayouts() {}

  public static ExtractLayout byId(String id) {
    ExtractLayout layout = BY_ID.get(id);
    if (layout == null) {
      throw new IllegalArgumentException("Unknown extract layout");
    }
    return layout;
  }

  public static ExtractLayout byFileName(String name) {
    String baseName = name.substring(name.lastIndexOf('/') + 1);
    if (baseName.startsWith("exposure_monthly.D") && baseName.endsWith(".csv")) {
      return MONTHLY_EXPOSURE;
    }
    ExtractLayout layout = BY_FILE.get(baseName);
    if (layout == null) {
      throw new IllegalArgumentException("Unknown extract file");
    }
    return layout;
  }

  public static List<String> exposureColumns() {
    return List.of(
        "POLICY_TYPE",
        "BRANCH_CODE",
        "COVERAGE_TYPE",
        "RATING_TERRITORY",
        "CLASS_CODE",
        "POLICY_COUNT",
        "TOTAL_EXPOSURE",
        "TOTAL_PREMIUM",
        "TOTAL_DEDUCTIBLE");
  }
}
