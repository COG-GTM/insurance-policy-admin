package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.date;
import static com.acme.insurance.pas.persistence.Columns.decimal;
import static com.acme.insurance.pas.persistence.Columns.integer;
import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.Premium;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ACMEINS.POLICIES, COVERAGES and PREMIUMS; renewal and endorsement updates (AB-526). */
public class PolicyJdbcRepository {

  private static final RowMapper<Policy> POLICY_ROW =
      (rs, row) ->
          new Policy(
              text(rs, "policy_number"),
              text(rs, "policy_type"),
              text(rs, "policy_status"),
              date(rs, "effective_date"),
              date(rs, "expiry_date"),
              text(rs, "policyholder_id"),
              text(rs, "agent_code"),
              text(rs, "branch_code"),
              decimal(rs, "total_premium"),
              decimal(rs, "deductible"),
              decimal(rs, "coverage_limit"),
              date(rs, "inception_date"),
              integer(rs, "renewal_count"),
              text(rs, "uw_status"),
              integer(rs, "risk_score"),
              text(rs, "web_indicator"),
              text(rs, "api_flag"),
              timestamp(rs, "last_updated"),
              text(rs, "updated_by"));

  private final JdbcClient jdbc;

  public PolicyJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Next value of ACMEINS.POLICY_SEQ. Formatting it as a policy number is blocked on Q-08. */
  public long nextPolicySequence() {
    return jdbc.sql("SELECT nextval('acmeins.policy_seq')").query(Long.class).single();
  }

  public void insert(Policy p) {
    new Insert("acmeins.policies")
        .set("policy_number", p.policyNumber())
        .set("policy_type", p.policyTypeCode())
        .setOrDefault("policy_status", p.policyStatusCode())
        .set("effective_date", p.effectiveDate())
        .set("expiry_date", p.expiryDate())
        .set("policyholder_id", p.policyholderId())
        .set("agent_code", p.agentCode())
        .set("branch_code", p.branchCode())
        .set("total_premium", p.totalPremium())
        .set("deductible", p.deductible())
        .set("coverage_limit", p.coverageLimit())
        .set("inception_date", p.inceptionDate())
        .set("renewal_count", p.renewalCount())
        .set("uw_status", p.uwStatusCode())
        .set("risk_score", p.riskScore())
        .set("web_indicator", p.webIndicator())
        .set("api_flag", p.apiFlag())
        .setOrDefault("last_updated", p.lastUpdated())
        .setOrDefault("updated_by", p.updatedBy())
        .execute(jdbc);
  }

  /**
   * UNDWRT {@code 6000-WRITE-DECISION}: UW_STATUS and RISK_SCORE of the policy, LAST_UPDATED from
   * the database clock. Returns the number of rows changed (0 when the policy is gone).
   */
  public int updateUnderwriting(
      String policyNumber, String uwStatus, Integer riskScore, String updatedBy) {
    return jdbc.sql(
            """
            UPDATE acmeins.policies
               SET uw_status = ?, risk_score = ?, last_updated = CURRENT_TIMESTAMP, updated_by = ?
             WHERE policy_number = ?
            """)
        .params(uwStatus, riskScore, updatedBy, policyNumber)
        .update();
  }

  /**
   * UNDWRT {@code 4000-CHECK-ACCUMULATION}: total COVERAGE_LIMIT of the branch's active (AC)
   * policies, 0 when there are none.
   */
  public BigDecimal activeCoverageLimitForBranch(String branchCode) {
    return jdbc.sql(
            """
            SELECT COALESCE(SUM(coverage_limit), 0) FROM acmeins.policies
             WHERE branch_code = ? AND policy_status = 'AC'
            """)
        .param(branchCode)
        .query(BigDecimal.class)
        .single();
  }

  /**
   * Locks the policy row until the end of the transaction, so underwriting decisions and overrides
   * of one policy run one after the other. Returns false when the policy does not exist.
   */
  public boolean lockPolicy(String policyNumber) {
    return !jdbc.sql(
            "SELECT policy_number FROM acmeins.policies WHERE policy_number = ? FOR UPDATE")
        .param(policyNumber)
        .query(String.class)
        .list()
        .isEmpty();
  }

  public Optional<Policy> findPolicy(String policyNumber) {
    return jdbc.sql("SELECT * FROM acmeins.policies WHERE policy_number = ?")
        .param(policyNumber)
        .query(POLICY_ROW)
        .optional();
  }

  /**
   * Reads the policy and keeps it locked until the transaction ends, the guarantee POLEND gets from
   * {@code WITH RS USE AND KEEP UPDATE LOCKS}: concurrent renewals and endorsements of one policy
   * run one after the other and never overwrite each other's premium. Needs an open transaction.
   */
  public Optional<Policy> findPolicyForUpdate(String policyNumber) {
    return jdbc.sql("SELECT * FROM acmeins.policies WHERE policy_number = ? FOR UPDATE")
        .param(policyNumber)
        .query(POLICY_ROW)
        .optional();
  }

  /** POLRNW 6000-UPDATE-POLICY. Returns the number of rows updated. */
  public int updateRenewal(Policy renewed) {
    return jdbc.sql(
            """
            UPDATE acmeins.policies
               SET policy_status = ?, effective_date = ?, expiry_date = ?, total_premium = ?,
                   renewal_count = ?, uw_status = ?, last_updated = ?, updated_by = ?
             WHERE policy_number = ?
            """)
        .params(
            renewed.policyStatusCode(),
            renewed.effectiveDate(),
            renewed.expiryDate(),
            renewed.totalPremium(),
            renewed.renewalCount(),
            renewed.uwStatusCode(),
            renewed.lastUpdated(),
            renewed.updatedBy(),
            renewed.policyNumber())
        .update();
  }

  /** POLEND 5000-UPDATE-POLICY-PREMIUM. Returns the number of rows updated. */
  public int updateTotalPremium(
      String policyNumber, BigDecimal totalPremium, LocalDateTime lastUpdated, String updatedBy) {
    return jdbc.sql(
            """
            UPDATE acmeins.policies
               SET total_premium = ?, last_updated = ?, updated_by = ?
             WHERE policy_number = ?
            """)
        .params(totalPremium, lastUpdated, updatedBy, policyNumber)
        .update();
  }

  /** POLRNW 7000-UPDATE-COVERAGES: re-dates the active coverages to the new term. */
  public int redateActiveCoverages(
      String policyNumber, LocalDate effectiveDate, LocalDate expiryDate) {
    return jdbc.sql(
            """
            UPDATE acmeins.coverages SET effective_date = ?, expiry_date = ?
             WHERE policy_number = ? AND status = 'AC'
            """)
        .params(effectiveDate, expiryDate, policyNumber)
        .update();
  }

  public void insert(Coverage c) {
    new Insert("acmeins.coverages")
        .set("policy_number", c.policyNumber())
        .set("sequence_num", c.sequenceNum())
        .set("coverage_type", c.coverageTypeCode())
        .set("description", c.description())
        .set("coverage_limit", c.coverageLimit())
        .set("deductible", c.deductible())
        .set("premium", c.premium())
        .set("effective_date", c.effectiveDate())
        .set("expiry_date", c.expiryDate())
        .setOrDefault("status", c.statusCode())
        .set("coinsurance_pct", c.coinsurancePct())
        .set("rating_territory", c.ratingTerritory())
        .set("class_code", c.classCode())
        .execute(jdbc);
  }

  public List<Coverage> findCoverages(String policyNumber) {
    return jdbc.sql("SELECT * FROM acmeins.coverages WHERE policy_number = ? ORDER BY sequence_num")
        .param(policyNumber)
        .query(
            (rs, row) ->
                new Coverage(
                    text(rs, "policy_number"),
                    rs.getInt("sequence_num"),
                    text(rs, "coverage_type"),
                    text(rs, "description"),
                    decimal(rs, "coverage_limit"),
                    decimal(rs, "deductible"),
                    decimal(rs, "premium"),
                    date(rs, "effective_date"),
                    date(rs, "expiry_date"),
                    text(rs, "status"),
                    integer(rs, "coinsurance_pct"),
                    text(rs, "rating_territory"),
                    text(rs, "class_code")))
        .list();
  }

  public void insert(Premium p) {
    new Insert("acmeins.premiums")
        .set("policy_number", p.policyNumber())
        .set("coverage_seq", p.coverageSeq())
        .set("term_effective_date", p.termEffectiveDate())
        .set("term_expiry_date", p.termExpiryDate())
        .set("base_rate", p.baseRate())
        .set("territory_factor", p.territoryFactor())
        .set("class_factor", p.classFactor())
        .set("experience_mod", p.experienceMod())
        .set("schedule_mod", p.scheduleMod())
        .set("discount_pct", p.discountPct())
        .set("surcharge_amt", p.surchargeAmt())
        .set("tax_amt", p.taxAmt())
        .set("total_premium", p.totalPremium())
        .set("installment_code", p.installmentCode())
        .set("installment_amt", p.installmentAmt())
        .set("calc_date", p.calcDate())
        .set("calc_by", p.calcBy())
        .execute(jdbc);
  }

  public List<Premium> findPremiums(String policyNumber) {
    return jdbc.sql(
            """
            SELECT * FROM acmeins.premiums WHERE policy_number = ?
            ORDER BY coverage_seq, term_effective_date
            """)
        .param(policyNumber)
        .query(
            (rs, row) ->
                new Premium(
                    text(rs, "policy_number"),
                    rs.getInt("coverage_seq"),
                    date(rs, "term_effective_date"),
                    date(rs, "term_expiry_date"),
                    decimal(rs, "base_rate"),
                    decimal(rs, "territory_factor"),
                    decimal(rs, "class_factor"),
                    decimal(rs, "experience_mod"),
                    decimal(rs, "schedule_mod"),
                    decimal(rs, "discount_pct"),
                    decimal(rs, "surcharge_amt"),
                    decimal(rs, "tax_amt"),
                    decimal(rs, "total_premium"),
                    text(rs, "installment_code"),
                    decimal(rs, "installment_amt"),
                    date(rs, "calc_date"),
                    text(rs, "calc_by")))
        .list();
  }
}
