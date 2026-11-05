package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.date;
import static com.acme.insurance.pas.persistence.Columns.decimal;
import static com.acme.insurance.pas.persistence.Columns.integer;
import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.Premium;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ACMEINS.POLICIES, COVERAGES and PREMIUMS. */
public class PolicyJdbcRepository {

  private final JdbcClient jdbc;

  public PolicyJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Next value of ACMEINS.POLICY_SEQ. Formatting it as a policy number is blocked on Q-08. */
  public long nextPolicySequence() {
    return jdbc.sql("SELECT nextval('acmeins.policy_seq')").query(Long.class).single();
  }

  public void insert(Policy p) {
    jdbc.sql(
            """
            INSERT INTO acmeins.policies (policy_number, policy_type, policy_status,
              effective_date, expiry_date, policyholder_id, agent_code, branch_code, total_premium,
              deductible, coverage_limit, inception_date, renewal_count, uw_status, risk_score,
              web_indicator, api_flag, last_updated, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            p.policyNumber(),
            p.policyTypeCode(),
            p.policyStatusCode(),
            p.effectiveDate(),
            p.expiryDate(),
            p.policyholderId(),
            p.agentCode(),
            p.branchCode(),
            p.totalPremium(),
            p.deductible(),
            p.coverageLimit(),
            p.inceptionDate(),
            p.renewalCount(),
            p.uwStatusCode(),
            p.riskScore(),
            p.webIndicator(),
            p.apiFlag(),
            p.lastUpdated(),
            p.updatedBy())
        .update();
  }

  public Optional<Policy> findPolicy(String policyNumber) {
    return jdbc.sql("SELECT * FROM acmeins.policies WHERE policy_number = ?")
        .param(policyNumber)
        .query(
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
                    text(rs, "updated_by")))
        .optional();
  }

  public void insert(Coverage c) {
    jdbc.sql(
            """
            INSERT INTO acmeins.coverages (policy_number, sequence_num, coverage_type, description,
              coverage_limit, deductible, premium, effective_date, expiry_date, status,
              coinsurance_pct, rating_territory, class_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            c.policyNumber(),
            c.sequenceNum(),
            c.coverageTypeCode(),
            c.description(),
            c.coverageLimit(),
            c.deductible(),
            c.premium(),
            c.effectiveDate(),
            c.expiryDate(),
            c.statusCode(),
            c.coinsurancePct(),
            c.ratingTerritory(),
            c.classCode())
        .update();
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
    jdbc.sql(
            """
            INSERT INTO acmeins.premiums (policy_number, coverage_seq, term_effective_date,
              term_expiry_date, base_rate, territory_factor, class_factor, experience_mod,
              schedule_mod, discount_pct, surcharge_amt, tax_amt, total_premium, installment_code,
              installment_amt, calc_date, calc_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            p.policyNumber(),
            p.coverageSeq(),
            p.termEffectiveDate(),
            p.termExpiryDate(),
            p.baseRate(),
            p.territoryFactor(),
            p.classFactor(),
            p.experienceMod(),
            p.scheduleMod(),
            p.discountPct(),
            p.surchargeAmt(),
            p.taxAmt(),
            p.totalPremium(),
            p.installmentCode(),
            p.installmentAmt(),
            p.calcDate(),
            p.calcBy())
        .update();
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
