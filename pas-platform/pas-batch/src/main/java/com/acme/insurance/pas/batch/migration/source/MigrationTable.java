package com.acme.insurance.pas.batch.migration.source;

import java.sql.Types;
import java.util.List;

public enum MigrationTable {
  POLICY_HOLDERS(
      "POLICY_HOLDERS",
      "policy_holders",
      List.of(
          "CUST_ID",
          "CUST_TYPE",
          "LAST_NAME",
          "FIRST_NAME",
          "MIDDLE_INIT",
          "COMPANY_NAME",
          "ADDR_LINE1",
          "ADDR_LINE2",
          "CITY",
          "STATE_CODE",
          "ZIP_CODE",
          "COUNTRY_CODE",
          "PHONE",
          "EMAIL",
          "DATE_OF_BIRTH",
          "SSN_LAST4",
          "TAX_ID",
          "CREDIT_SCORE",
          "RISK_TIER",
          "GDPR_CONSENT",
          "CREATED_DATE",
          "LAST_UPDATED"),
      List.of("CUST_ID"),
      List.of(Types.CHAR),
      "LAST_UPDATED"),
  POLICIES(
      "POLICIES",
      "policies",
      List.of(
          "POLICY_NUMBER",
          "POLICY_TYPE",
          "POLICY_STATUS",
          "EFFECTIVE_DATE",
          "EXPIRY_DATE",
          "POLICYHOLDER_ID",
          "AGENT_CODE",
          "BRANCH_CODE",
          "TOTAL_PREMIUM",
          "DEDUCTIBLE",
          "COVERAGE_LIMIT",
          "INCEPTION_DATE",
          "RENEWAL_COUNT",
          "UW_STATUS",
          "RISK_SCORE",
          "WEB_INDICATOR",
          "API_FLAG",
          "LAST_UPDATED",
          "UPDATED_BY"),
      List.of("POLICY_NUMBER"),
      List.of(Types.CHAR),
      "LAST_UPDATED"),
  COVERAGES(
      "COVERAGES",
      "coverages",
      List.of(
          "POLICY_NUMBER",
          "SEQUENCE_NUM",
          "COVERAGE_TYPE",
          "DESCRIPTION",
          "COVERAGE_LIMIT",
          "DEDUCTIBLE",
          "PREMIUM",
          "EFFECTIVE_DATE",
          "EXPIRY_DATE",
          "STATUS",
          "COINSURANCE_PCT",
          "RATING_TERRITORY",
          "CLASS_CODE"),
      List.of("POLICY_NUMBER", "SEQUENCE_NUM"),
      List.of(Types.CHAR, Types.SMALLINT),
      null),
  PREMIUMS(
      "PREMIUMS",
      "premiums",
      List.of(
          "POLICY_NUMBER",
          "COVERAGE_SEQ",
          "TERM_EFFECTIVE_DATE",
          "TERM_EXPIRY_DATE",
          "BASE_RATE",
          "TERRITORY_FACTOR",
          "CLASS_FACTOR",
          "EXPERIENCE_MOD",
          "SCHEDULE_MOD",
          "DISCOUNT_PCT",
          "SURCHARGE_AMT",
          "TAX_AMT",
          "TOTAL_PREMIUM",
          "INSTALLMENT_CODE",
          "INSTALLMENT_AMT",
          "CALC_DATE",
          "CALC_BY"),
      List.of("POLICY_NUMBER", "COVERAGE_SEQ", "TERM_EFFECTIVE_DATE"),
      List.of(Types.CHAR, Types.SMALLINT, Types.DATE),
      "CALC_DATE"),
  ENDORSEMENTS(
      "ENDORSEMENTS",
      "endorsements",
      List.of(
          "POLICY_NUMBER",
          "ENDORSEMENT_SEQ",
          "ENDORSEMENT_TYPE",
          "EFFECTIVE_DATE",
          "DESCRIPTION",
          "PREMIUM_ADJUSTMENT",
          "PROCESSED_DATE",
          "PROCESSED_BY"),
      List.of("POLICY_NUMBER", "ENDORSEMENT_SEQ"),
      List.of(Types.CHAR, Types.INTEGER),
      "PROCESSED_DATE"),
  UNDERWRITING_DECISIONS(
      "UNDERWRITING_DECISIONS",
      "underwriting_decisions",
      List.of(
          "POLICY_NUMBER",
          "DECISION_DATE",
          "DECISION_CODE",
          "RISK_SCORE",
          "DECISION_REASON",
          "UNDERWRITER_ID",
          "OVERRIDE_REASON",
          "OVERRIDE_BY",
          "CREATED_TIMESTAMP"),
      List.of("POLICY_NUMBER", "DECISION_DATE"),
      List.of(Types.CHAR, Types.DATE),
      "CREATED_TIMESTAMP"),
  TERRITORY_FACTORS(
      "TERRITORY_FACTORS",
      "territory_factors",
      List.of("TERRITORY_CODE", "EFFECTIVE_DATE", "RATING_FACTOR"),
      List.of("TERRITORY_CODE", "EFFECTIVE_DATE"),
      List.of(Types.CHAR, Types.DATE),
      null);

  private final String sourceName;
  private final String targetName;
  private final List<String> columns;
  private final List<String> keyColumns;
  private final List<Integer> keyTypes;
  private final String changeColumn;

  MigrationTable(
      String sourceName,
      String targetName,
      List<String> columns,
      List<String> keyColumns,
      List<Integer> keyTypes,
      String changeColumn) {
    this.sourceName = sourceName;
    this.targetName = targetName;
    this.columns = columns;
    this.keyColumns = keyColumns;
    this.keyTypes = keyTypes;
    this.changeColumn = changeColumn;
  }

  public String sourceName() {
    return sourceName;
  }

  public String targetName() {
    return targetName;
  }

  public List<String> columns() {
    return List.copyOf(columns);
  }

  public List<String> keyColumns() {
    return List.copyOf(keyColumns);
  }

  public List<Integer> keyTypes() {
    return List.copyOf(keyTypes);
  }

  public String changeColumn() {
    return changeColumn;
  }

  public String leadingKey() {
    return keyColumns.getFirst();
  }

  public static List<MigrationTable> bulkLoadOrder() {
    return List.of(
        POLICY_HOLDERS,
        POLICIES,
        COVERAGES,
        PREMIUMS,
        ENDORSEMENTS,
        UNDERWRITING_DECISIONS,
        TERRITORY_FACTORS);
  }
}
