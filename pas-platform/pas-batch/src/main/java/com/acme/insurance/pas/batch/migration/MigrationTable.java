package com.acme.insurance.pas.batch.migration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

/**
 * The ACMEINS tables moved to {@code pas.*}, in foreign-key load order. Column names are identical
 * on both sides except the PII columns of POLICY_HOLDERS, which are encrypted on the way in ({@link
 * PolicyHolderPii}). TERRITORY_FACTORS is not loaded: PREMBAT never reads it (Q-03) and filling the
 * {@code FILED} factor set is a business decision (OQ-04); reconciliation still counts it.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "returns an immutable List.of / shared pool or mapper by design")
public enum MigrationTable {
  POLICY_HOLDERS(
      "POLICY_HOLDERS",
      "pas.policy_holders",
      List.of("cust_id"),
      List.of(
          "cust_id",
          "cust_type",
          "last_name",
          "first_name",
          "middle_init",
          "company_name",
          "addr_line1",
          "addr_line2",
          "city",
          "state_code",
          "zip_code",
          "country_code",
          "phone",
          "email",
          "risk_tier",
          "gdpr_consent",
          "created_date",
          "last_updated"),
      "last_updated"),
  POLICIES(
      "POLICIES",
      "pas.policies",
      List.of("policy_number"),
      List.of(
          "policy_number",
          "policy_type",
          "policy_status",
          "effective_date",
          "expiry_date",
          "policyholder_id",
          "agent_code",
          "branch_code",
          "total_premium",
          "deductible",
          "coverage_limit",
          "inception_date",
          "renewal_count",
          "uw_status",
          "risk_score",
          "web_indicator",
          "api_flag",
          "last_updated",
          "updated_by"),
      "last_updated"),
  COVERAGES(
      "COVERAGES",
      "pas.coverages",
      List.of("policy_number", "sequence_num"),
      List.of(
          "policy_number",
          "sequence_num",
          "coverage_type",
          "description",
          "coverage_limit",
          "deductible",
          "premium",
          "effective_date",
          "expiry_date",
          "status",
          "coinsurance_pct",
          "rating_territory",
          "class_code"),
      null),
  PREMIUMS(
      "PREMIUMS",
      "pas.premiums",
      List.of("policy_number", "coverage_seq", "term_effective_date"),
      List.of(
          "policy_number",
          "coverage_seq",
          "term_effective_date",
          "term_expiry_date",
          "base_rate",
          "territory_factor",
          "class_factor",
          "experience_mod",
          "schedule_mod",
          "discount_pct",
          "surcharge_amt",
          "tax_amt",
          "total_premium",
          "installment_code",
          "installment_amt",
          "calc_date",
          "calc_by"),
      "calc_date"),
  ENDORSEMENTS(
      "ENDORSEMENTS",
      "pas.endorsements",
      List.of("policy_number", "endorsement_seq"),
      List.of(
          "policy_number",
          "endorsement_seq",
          "endorsement_type",
          "effective_date",
          "description",
          "premium_adjustment",
          "processed_date",
          "processed_by"),
      "processed_date"),
  /**
   * DB2 key is (POLICY_NUMBER, DECISION_DATE); the target uses a surrogate {@code decision_id}
   * (Q-20), so rows are matched on the DB2 key and never duplicated on re-run.
   */
  UNDERWRITING_DECISIONS(
      "UNDERWRITING_DECISIONS",
      "pas.underwriting_decisions",
      List.of("policy_number", "decision_date"),
      List.of(
          "policy_number",
          "decision_date",
          "decision_code",
          "risk_score",
          "decision_reason",
          "underwriter_id",
          "override_reason",
          "override_by",
          "created_timestamp"),
      "created_timestamp");

  private final String sourceTable;
  private final String targetTable;
  private final List<String> keyColumns;
  private final List<String> columns;
  private final String watermarkColumn;

  MigrationTable(
      String sourceTable,
      String targetTable,
      List<String> keyColumns,
      List<String> columns,
      String watermarkColumn) {
    this.sourceTable = sourceTable;
    this.targetTable = targetTable;
    this.keyColumns = keyColumns;
    this.columns = columns;
    this.watermarkColumn = watermarkColumn;
  }

  public String sourceTable() {
    return sourceTable;
  }

  public String targetTable() {
    return targetTable;
  }

  public List<String> keyColumns() {
    return keyColumns;
  }

  /** Columns read from the source, in DDL order (PII source columns appended for holders). */
  public List<String> sourceColumns() {
    if (this == POLICY_HOLDERS) {
      return PolicyHolderPii.withSourcePiiColumns(columns);
    }
    return columns;
  }

  /** Non-PII columns written verbatim to the target. */
  public List<String> columns() {
    return columns;
  }

  /** Source change-tracking column for the delta sync, or {@code null} (see data-migration.md). */
  public String watermarkColumn() {
    return watermarkColumn;
  }

  /** PREMIUMS.CALC_DATE is a DATE, so its watermark has day granularity. */
  public boolean dateWatermark() {
    return this == PREMIUMS;
  }
}
