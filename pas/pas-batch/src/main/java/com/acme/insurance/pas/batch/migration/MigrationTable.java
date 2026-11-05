package com.acme.insurance.pas.batch.migration;

import java.util.List;

/**
 * The seven ACMEINS tables PAS owns, with their key and column lists as in the frozen DB2 DDL
 * (sql/ddl/create-tables.sql). The Claims table is owned by the Claims team and never migrated.
 * Order is parent before child, the order inserts are applied in; deletes use the reverse order.
 */
public enum MigrationTable {
  POLICY_HOLDERS(
      List.of("CUST_ID"),
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
          "LAST_UPDATED")),
  TERRITORY_FACTORS(
      List.of("TERRITORY_CODE", "EFFECTIVE_DATE"),
      List.of("TERRITORY_CODE", "EFFECTIVE_DATE", "RATING_FACTOR")),
  POLICIES(
      List.of("POLICY_NUMBER"),
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
          "UPDATED_BY")),
  COVERAGES(
      List.of("POLICY_NUMBER", "SEQUENCE_NUM"),
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
          "CLASS_CODE")),
  PREMIUMS(
      List.of("POLICY_NUMBER", "COVERAGE_SEQ", "TERM_EFFECTIVE_DATE"),
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
          "CALC_BY")),
  ENDORSEMENTS(
      List.of("POLICY_NUMBER", "ENDORSEMENT_SEQ"),
      List.of(
          "POLICY_NUMBER",
          "ENDORSEMENT_SEQ",
          "ENDORSEMENT_TYPE",
          "EFFECTIVE_DATE",
          "DESCRIPTION",
          "PREMIUM_ADJUSTMENT",
          "PROCESSED_DATE",
          "PROCESSED_BY")),
  /**
   * PostgreSQL keys this table by a generated DECISION_ID (V13, Q-18). While DB2 is the system of
   * record rows are matched on the DB2 key (POLICY_NUMBER, DECISION_DATE); DECISION_ID is never
   * written by the sync.
   */
  UNDERWRITING_DECISIONS(
      List.of("POLICY_NUMBER", "DECISION_DATE"),
      List.of(
          "POLICY_NUMBER",
          "DECISION_DATE",
          "DECISION_CODE",
          "RISK_SCORE",
          "DECISION_REASON",
          "UNDERWRITER_ID",
          "OVERRIDE_REASON",
          "OVERRIDE_BY",
          "CREATED_TIMESTAMP"));

  private final List<String> keyColumns;
  private final List<String> columns;

  MigrationTable(List<String> keyColumns, List<String> columns) {
    this.keyColumns = keyColumns;
    this.columns = columns;
  }

  public String qualifiedName() {
    return "ACMEINS." + name();
  }

  public List<String> keyColumns() {
    return keyColumns;
  }

  public List<String> columns() {
    return columns;
  }

  public List<String> valueColumns() {
    return columns.stream().filter(c -> !keyColumns.contains(c)).toList();
  }

  /** First key column: every table is bucketed on the trailing characters of it. */
  public String bucketColumn() {
    return keyColumns.get(0);
  }
}
