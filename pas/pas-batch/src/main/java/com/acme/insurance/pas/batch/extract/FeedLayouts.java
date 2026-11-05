package com.acme.insurance.pas.batch.extract;

import static com.acme.insurance.pas.batch.extract.FeedLayout.Field.date;
import static com.acme.insurance.pas.batch.extract.FeedLayout.Field.decimal;
import static com.acme.insurance.pas.batch.extract.FeedLayout.Field.integer;
import static com.acme.insurance.pas.batch.extract.FeedLayout.Field.text;

import com.acme.insurance.pas.batch.extract.FeedLayout.Format;
import java.util.List;

/**
 * Candidate layouts of the three feeds. Fields follow the SELECT lists of the JCL in order and the
 * column types of sql/ddl/create-tables.sql; record lengths are the LRECLs of the JCL DDs, which
 * compat/check_layouts.py protects.
 */
public final class FeedLayouts {

  private FeedLayouts() {}

  /** POLOUT, LRECL 200: 104 bytes of fields, then spaces. */
  public static final FeedLayout CLAIMS_POLICY =
      new FeedLayout(
          "POLOUT",
          Format.FIXED_WIDTH,
          200,
          List.of(
              text("POLICY_NUMBER", 12),
              text("POLICY_TYPE", 3),
              text("POLICY_STATUS", 2),
              date("EFFECTIVE_DATE"),
              date("EXPIRY_DATE"),
              text("POLICYHOLDER_ID", 10),
              text("AGENT_CODE", 6),
              text("BRANCH_CODE", 4),
              decimal("TOTAL_PREMIUM", 11, 2),
              decimal("DEDUCTIBLE", 9, 2),
              decimal("COVERAGE_LIMIT", 13, 2),
              text("UW_STATUS", 2),
              integer("RISK_SCORE", 5)));

  /** COVOUT, LRECL 150: 123 bytes of fields, then spaces. */
  public static final FeedLayout CLAIMS_COVERAGE =
      new FeedLayout(
          "COVOUT",
          Format.FIXED_WIDTH,
          150,
          List.of(
              text("POLICY_NUMBER", 12),
              integer("SEQUENCE_NUM", 5),
              text("COVERAGE_TYPE", 4),
              text("DESCRIPTION", 40),
              decimal("COVERAGE_LIMIT", 13, 2),
              decimal("DEDUCTIBLE", 9, 2),
              decimal("PREMIUM", 11, 2),
              date("EFFECTIVE_DATE"),
              date("EXPIRY_DATE"),
              text("STATUS", 2)));

  /** EXPOUT, LRECL 300: CSV with a header line. SUMs are DB2 DECIMAL(31,s), COUNT is INTEGER. */
  public static final FeedLayout ACTUARIAL_EXPOSURE =
      new FeedLayout(
          "EXPOUT",
          Format.CSV,
          300,
          List.of(
              text("POLICY_TYPE", 3),
              text("BRANCH_CODE", 4),
              text("COVERAGE_TYPE", 4),
              text("RATING_TERRITORY", 6),
              text("CLASS_CODE", 5),
              integer("POLICY_COUNT", 10),
              decimal("TOTAL_EXPOSURE", 31, 2),
              decimal("TOTAL_PREMIUM", 31, 2),
              decimal("TOTAL_DEDUCTIBLE", 31, 2)));
}
