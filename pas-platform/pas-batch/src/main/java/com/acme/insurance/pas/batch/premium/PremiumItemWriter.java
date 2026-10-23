package com.acme.insurance.pas.batch.premium;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

public class PremiumItemWriter implements ItemWriter<PremiumRow> {
  private static final String PAS_UPSERT =
      "INSERT INTO pas.premiums "
          + "(policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate, "
          + "territory_factor, class_factor, experience_mod, schedule_mod, discount_pct, "
          + "surcharge_amt, tax_amt, total_premium, installment_code, installment_amt, calc_date, calc_by) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
          + "ON CONFLICT (policy_number, coverage_seq, term_effective_date) DO UPDATE SET "
          + "term_expiry_date = EXCLUDED.term_expiry_date, base_rate = EXCLUDED.base_rate, "
          + "territory_factor = EXCLUDED.territory_factor, class_factor = EXCLUDED.class_factor, "
          + "experience_mod = EXCLUDED.experience_mod, schedule_mod = EXCLUDED.schedule_mod, "
          + "discount_pct = EXCLUDED.discount_pct, surcharge_amt = EXCLUDED.surcharge_amt, "
          + "tax_amt = EXCLUDED.tax_amt, total_premium = EXCLUDED.total_premium, "
          + "installment_code = EXCLUDED.installment_code, installment_amt = EXCLUDED.installment_amt, "
          + "calc_date = EXCLUDED.calc_date, calc_by = EXCLUDED.calc_by";
  private static final String SHADOW_UPSERT =
      "INSERT INTO pas_shadow.premiums "
          + "(policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate, "
          + "territory_factor, class_factor, experience_mod, schedule_mod, discount_pct, "
          + "surcharge_amt, tax_amt, total_premium, installment_code, installment_amt, calc_date, "
          + "calc_by, business_date, job_instance_id) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
          + "ON CONFLICT (business_date, policy_number, coverage_seq, term_effective_date) "
          + "DO UPDATE SET term_expiry_date = EXCLUDED.term_expiry_date, "
          + "base_rate = EXCLUDED.base_rate, territory_factor = EXCLUDED.territory_factor, "
          + "class_factor = EXCLUDED.class_factor, experience_mod = EXCLUDED.experience_mod, "
          + "schedule_mod = EXCLUDED.schedule_mod, discount_pct = EXCLUDED.discount_pct, "
          + "surcharge_amt = EXCLUDED.surcharge_amt, tax_amt = EXCLUDED.tax_amt, "
          + "total_premium = EXCLUDED.total_premium, installment_code = EXCLUDED.installment_code, "
          + "installment_amt = EXCLUDED.installment_amt, calc_date = EXCLUDED.calc_date, "
          + "calc_by = EXCLUDED.calc_by, job_instance_id = EXCLUDED.job_instance_id";

  private final JdbcTemplate jdbcTemplate;
  private final String target;
  private final long jobInstanceId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The Spring-managed JDBC template is retained for transactional writes.")
  public PremiumItemWriter(JdbcTemplate jdbcTemplate, String target, long jobInstanceId) {
    this.jdbcTemplate = jdbcTemplate;
    this.target = target;
    this.jobInstanceId = jobInstanceId;
  }

  @Override
  public void write(Chunk<? extends PremiumRow> chunk) {
    for (PremiumRow row : chunk.getItems()) {
      if ("SHADOW".equals(target)) {
        jdbcTemplate.update(SHADOW_UPSERT, parameters(row, true));
      } else {
        jdbcTemplate.update(PAS_UPSERT, parameters(row, false));
      }
      jdbcTemplate.update(
          "INSERT INTO pas_batch.premium_run_item "
              + "(job_instance_id, policy_number, status, total_premium, error_type) "
              + "VALUES (?, ?, 'UPDATED', ?, NULL) "
              + "ON CONFLICT (job_instance_id, policy_number) DO UPDATE SET "
              + "status = 'UPDATED', total_premium = EXCLUDED.total_premium, error_type = NULL",
          jobInstanceId,
          row.policyNumber(),
          row.totalPremium());
    }
  }

  private Object[] parameters(PremiumRow row, boolean shadow) {
    if (shadow) {
      return new Object[] {
        row.policyNumber(),
        row.coverageSequence(),
        row.termEffectiveDate(),
        row.termExpiryDate(),
        row.baseRate(),
        row.territoryFactor(),
        row.classFactor(),
        row.experienceMod(),
        row.scheduleMod(),
        row.discountPct(),
        row.surchargeAmount(),
        row.taxAmount(),
        row.totalPremium(),
        row.installmentCode(),
        row.installmentAmount(),
        row.calculationDate(),
        row.calculatedBy(),
        row.calculationDate(),
        jobInstanceId
      };
    }
    return new Object[] {
      row.policyNumber(),
      row.coverageSequence(),
      row.termEffectiveDate(),
      row.termExpiryDate(),
      row.baseRate(),
      row.territoryFactor(),
      row.classFactor(),
      row.experienceMod(),
      row.scheduleMod(),
      row.discountPct(),
      row.surchargeAmount(),
      row.taxAmount(),
      row.totalPremium(),
      row.installmentCode(),
      row.installmentAmount(),
      row.calculationDate(),
      row.calculatedBy()
    };
  }
}
