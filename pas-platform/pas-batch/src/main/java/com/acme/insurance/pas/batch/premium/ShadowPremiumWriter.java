package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.common.domain.PremiumRecord;
import java.time.LocalDate;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Parallel-run writer ({@link PremiumWriteTarget#SHADOW}): writes the same PREMIUMS rows as {@link
 * PremiumUpsertWriter} to {@code pas_shadow.premiums}, tagged with {@link #shadowRunId} of the job
 * instance and the run's business date. A restart keeps the job instance and so upserts into the
 * same shadow run; {@code rerun=true} starts a new instance and a new shadow run.
 */
public class ShadowPremiumWriter implements ItemWriter<PremiumRecord> {

  static final String UPSERT_SQL =
      """
      INSERT INTO pas_shadow.premiums (
          shadow_run_id, business_date,
          policy_number, coverage_seq, term_effective_date, term_expiry_date, base_rate,
          territory_factor, class_factor, experience_mod, schedule_mod, discount_pct,
          surcharge_amt, tax_amt, total_premium, installment_code, installment_amt,
          calc_date, calc_by)
      VALUES (
          :shadowRunId, :businessDate,
          :policyNumber, :coverageSeq, :termEffectiveDate, :termExpiryDate, :baseRate,
          :territoryFactor, :classFactor, :experienceMod, :scheduleMod, :discountPct,
          :surchargeAmt, :taxAmt, :totalPremium, :installmentCode, :installmentAmt,
          :calcDate, :calcBy)
      ON CONFLICT ON CONSTRAINT pk_shadow_premiums DO UPDATE SET
          business_date = EXCLUDED.business_date,
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
  private final String shadowRunId;
  private final LocalDate businessDate;

  public ShadowPremiumWriter(DataSource dataSource, long jobInstanceId, LocalDate businessDate) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    this.shadowRunId = shadowRunId(jobInstanceId);
    this.businessDate = businessDate;
  }

  /**
   * Sortable shadow run id of a job instance, e.g. {@code premiumRecalculationJob-000000000042}.
   */
  public static String shadowRunId(long jobInstanceId) {
    return PremiumRecalculationJobConfig.JOB_NAME
        + "-"
        + String.format(Locale.ROOT, "%012d", jobInstanceId);
  }

  @Override
  public void write(Chunk<? extends PremiumRecord> chunk) {
    SqlParameterSource[] batch =
        chunk.getItems().stream()
            .map(
                r ->
                    PremiumUpsertWriter.params(r)
                        .addValue("shadowRunId", shadowRunId)
                        .addValue("businessDate", businessDate))
            .toArray(SqlParameterSource[]::new);
    jdbc.batchUpdate(UPSERT_SQL, batch);
  }
}
