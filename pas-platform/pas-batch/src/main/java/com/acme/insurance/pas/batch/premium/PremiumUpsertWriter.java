package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.common.domain.PremiumRecord;
import javax.sql.DataSource;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Writes PREMIUMS rows in JDBC batches. PREMBAT does a plain INSERT, so a second run in the same
 * term fails with -803 for every policy (quirk Q-22); this writer upserts on the primary key so a
 * rerun replaces the row (Q-22 decision: Fix, rerun replaces).
 */
public class PremiumUpsertWriter implements ItemWriter<PremiumRecord> {

  static final String UPSERT_SQL =
      """
      INSERT INTO pas.premiums (
          policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate,
          territory_factor, class_factor, experience_mod, schedule_mod, discount_pct,
          surcharge_amt, tax_amt, total_premium, installment_code, installment_amt,
          calc_date, calc_by)
      VALUES (
          :policyNumber, :coverageSeq, :termEffectiveDate, :termExpiryDate, :baseRate,
          :territoryFactor, :classFactor, :experienceMod, :scheduleMod, :discountPct,
          :surchargeAmt, :taxAmt, :totalPremium, :installmentCode, :installmentAmt,
          :calcDate, :calcBy)
      ON CONFLICT ON CONSTRAINT pk_premiums DO UPDATE SET
          term_expiry_date = EXCLUDED.term_expiry_date,
          base_rate = EXCLUDED.base_rate,
          territory_factor = EXCLUDED.territory_factor,
          class_factor = EXCLUDED.class_factor,
          experience_mod = EXCLUDED.experience_mod,
          schedule_mod = EXCLUDED.schedule_mod,
          discount_pct = EXCLUDED.discount_pct,
          surcharge_amt = EXCLUDED.surcharge_amt,
          tax_amt = EXCLUDED.tax_amt,
          total_premium = EXCLUDED.total_premium,
          installment_code = EXCLUDED.installment_code,
          installment_amt = EXCLUDED.installment_amt,
          calc_date = EXCLUDED.calc_date,
          calc_by = EXCLUDED.calc_by
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public PremiumUpsertWriter(DataSource dataSource) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  @Override
  public void write(Chunk<? extends PremiumRecord> chunk) {
    SqlParameterSource[] batch =
        chunk.getItems().stream()
            .map(PremiumUpsertWriter::params)
            .toArray(SqlParameterSource[]::new);
    jdbc.batchUpdate(UPSERT_SQL, batch);
  }

  private static SqlParameterSource params(PremiumRecord r) {
    return new MapSqlParameterSource()
        .addValue("policyNumber", r.policyNumber())
        .addValue("coverageSeq", r.coverageSeq())
        .addValue("termEffectiveDate", r.termEffectiveDate())
        .addValue("termExpiryDate", r.termExpiryDate())
        .addValue("baseRate", r.baseRate())
        .addValue("territoryFactor", r.territoryFactor())
        .addValue("classFactor", r.classFactor())
        .addValue("experienceMod", r.experienceMod())
        .addValue("scheduleMod", r.scheduleMod())
        .addValue("discountPct", r.discountPct())
        .addValue("surchargeAmt", r.surchargeAmt())
        .addValue("taxAmt", r.taxAmt())
        .addValue("totalPremium", r.totalPremium())
        .addValue("installmentCode", r.installmentCode())
        .addValue("installmentAmt", r.installmentAmt())
        .addValue("calcDate", r.calcDate())
        .addValue("calcBy", r.calcBy());
  }
}
