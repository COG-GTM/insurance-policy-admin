package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.InstallmentCode;
import com.acme.insurance.pas.domain.Premium;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PremiumRepository {
  private static final String SELECT =
      """
      SELECT policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate,
             territory_factor, class_factor, experience_mod, schedule_mod, discount_pct,
             surcharge_amt, tax_amt, total_premium, installment_code, installment_amt,
             calc_date, calc_by
      FROM pas.premiums
      """;

  private final JdbcClient jdbc;

  public PremiumRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Premium premium) {
    jdbc.sql(
            """
            INSERT INTO pas.premiums (
                policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate,
                territory_factor, class_factor, experience_mod, schedule_mod, discount_pct,
                surcharge_amt, tax_amt, total_premium, installment_code, installment_amt,
                calc_date, calc_by
            ) VALUES (
                :policyNumber, :coverageSeq, :termEffectiveDate, :termExpiryDate, :baseRate,
                :territoryFactor, :classFactor, :experienceMod, :scheduleMod, :discountPct,
                :surchargeAmt, :taxAmt, :totalPremium, :installmentCode, :installmentAmt,
                :calcDate, :calcBy
            )
            """)
        .param("policyNumber", premium.policyNumber())
        .param("coverageSeq", premium.coverageSeq())
        .param("termEffectiveDate", premium.termEffectiveDate())
        .param("termExpiryDate", premium.termExpiryDate())
        .param("baseRate", premium.baseRate())
        .param("territoryFactor", premium.territoryFactor())
        .param("classFactor", premium.classFactor())
        .param("experienceMod", premium.experienceMod())
        .param("scheduleMod", premium.scheduleMod())
        .param("discountPct", premium.discountPct())
        .param("surchargeAmt", premium.surchargeAmt())
        .param("taxAmt", premium.taxAmt())
        .param("totalPremium", premium.totalPremium())
        .param(
            "installmentCode",
            premium.installmentCode() == null ? null : premium.installmentCode().raw())
        .param("installmentAmt", premium.installmentAmt())
        .param("calcDate", premium.calcDate())
        .param("calcBy", premium.calcBy())
        .update();
  }

  public Optional<Premium> findByKey(
      String policyNumber, int coverageSeq, LocalDate termEffectiveDate) {
    return jdbc.sql(
            SELECT
                + """
                   WHERE policy_number = :policyNumber AND coverage_seq = :coverageSeq
                     AND term_effective_date = :termEffectiveDate
                   """)
        .param("policyNumber", policyNumber)
        .param("coverageSeq", coverageSeq)
        .param("termEffectiveDate", termEffectiveDate)
        .query((row, rowNumber) -> map(row))
        .optional();
  }

  private static Premium map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new Premium(
        JdbcValues.charValue(row, "policy_number"),
        JdbcValues.integer(row, "coverage_seq"),
        JdbcValues.date(row, "term_effective_date"),
        JdbcValues.date(row, "term_expiry_date"),
        row.getBigDecimal("base_rate"),
        row.getBigDecimal("territory_factor"),
        row.getBigDecimal("class_factor"),
        row.getBigDecimal("experience_mod"),
        row.getBigDecimal("schedule_mod"),
        row.getBigDecimal("discount_pct"),
        row.getBigDecimal("surcharge_amt"),
        row.getBigDecimal("tax_amt"),
        row.getBigDecimal("total_premium"),
        JdbcValues.coded(row, "installment_code", InstallmentCode.class),
        row.getBigDecimal("installment_amt"),
        JdbcValues.date(row, "calc_date"),
        JdbcValues.charValue(row, "calc_by"));
  }
}
