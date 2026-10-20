package com.acme.insurance.pas.batch.extract;

import com.acme.insurance.pas.common.copybook.CopybookField;
import com.acme.insurance.pas.common.copybook.RecordLayout;

/**
 * Fixed-width DAILY-EXTRACT record layouts (RECFM=FB). Field order follows the SELECT list in
 * {@code jcl/DAILY-EXTRACT.jcl}; widths follow the DB2 column definitions. See {@code
 * docs/modernization/extract-layouts.md}.
 */
public final class ExtractRecordLayouts {

  /** {@code ACME.PAS.EXTRACT.POLICY.DAILY}, LRECL=200. */
  public static final RecordLayout POLICY_EXTRACT =
      RecordLayout.builder("POLICY-EXTRACT")
          .field(CopybookField.alphanumeric("POLICY_NUMBER", 12))
          .field(CopybookField.alphanumeric("POLICY_TYPE", 3))
          .field(CopybookField.alphanumeric("POLICY_STATUS", 2))
          .field(CopybookField.date("EFFECTIVE_DATE"))
          .field(CopybookField.date("EXPIRY_DATE"))
          .field(CopybookField.alphanumeric("POLICYHOLDER_ID", 10))
          .field(CopybookField.alphanumeric("AGENT_CODE", 6))
          .field(CopybookField.alphanumeric("BRANCH_CODE", 4))
          .field(CopybookField.zoned("TOTAL_PREMIUM", 9, 2, true))
          .field(CopybookField.zoned("DEDUCTIBLE", 7, 2, true))
          .field(CopybookField.zoned("COVERAGE_LIMIT", 11, 2, true))
          .field(CopybookField.alphanumeric("UW_STATUS", 2))
          .field(CopybookField.zoned("RISK_SCORE", 5, 0, true))
          .field(CopybookField.filler(107))
          .build();

  /** {@code ACME.PAS.EXTRACT.COVERAGE.DAILY}, LRECL=150. */
  public static final RecordLayout COVERAGE_EXTRACT =
      RecordLayout.builder("COVERAGE-EXTRACT")
          .field(CopybookField.alphanumeric("POLICY_NUMBER", 12))
          .field(CopybookField.zoned("SEQUENCE_NUM", 5, 0, true))
          .field(CopybookField.alphanumeric("COVERAGE_TYPE", 4))
          .field(CopybookField.alphanumeric("DESCRIPTION", 40))
          .field(CopybookField.zoned("COVERAGE_LIMIT", 11, 2, true))
          .field(CopybookField.zoned("DEDUCTIBLE", 7, 2, true))
          .field(CopybookField.zoned("PREMIUM", 9, 2, true))
          .field(CopybookField.date("EFFECTIVE_DATE"))
          .field(CopybookField.date("EXPIRY_DATE"))
          .field(CopybookField.alphanumeric("STATUS", 2))
          .field(CopybookField.filler(38))
          .build();

  public static final int POLICY_LRECL = 200;
  public static final int COVERAGE_LRECL = 150;

  private ExtractRecordLayouts() {}
}
