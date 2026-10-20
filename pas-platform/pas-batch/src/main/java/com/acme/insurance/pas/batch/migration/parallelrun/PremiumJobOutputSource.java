package com.acme.insurance.pas.batch.migration.parallelrun;

import com.acme.insurance.pas.batch.premium.PremiumRecalculationJobConfig;
import com.acme.insurance.pas.batch.premium.ShadowPremiumWriter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * New side of the PREMIUMS comparison: the rows the AB-523 {@code premiumRecalculationJob} wrote
 * for the business date in shadow mode ({@code pas.batch.premium.write-target=shadow}).
 *
 * <p>The run is the latest {@code COMPLETED} execution for that date in {@code
 * pas_batch.premium_run_summary} (a restart shares the job instance; {@code rerun=true} creates a
 * newer one). Its rows are {@code pas_shadow.premiums WHERE shadow_run_id =
 * ShadowPremiumWriter.shadowRunId(job_instance_id)}. No completed run, or a run that wrote {@code
 * pas.premiums} instead of the shadow table, means the output is not available and the day is
 * INCOMPLETE.
 */
public final class PremiumJobOutputSource implements PremiumOutputSource {

  static final String LATEST_COMPLETED_RUN =
      "SELECT job_instance_id FROM pas_batch.premium_run_summary"
          + " WHERE run_date = ? AND batch_status = 'COMPLETED'"
          + " ORDER BY ended_at DESC NULLS LAST, job_execution_id DESC LIMIT 1";

  private static final String ROWS =
      "SELECT "
          + String.join(", ", JdbcPremiumOutputSource.COLUMNS)
          + " FROM pas_shadow.premiums WHERE shadow_run_id = ?";

  private final JdbcTemplate jdbc;

  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "shared Spring JdbcTemplate bean")
  public PremiumJobOutputSource(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String location() {
    return "pas_shadow.premiums of the latest COMPLETED "
        + PremiumRecalculationJobConfig.JOB_NAME
        + " run for the business date (pas.batch.premium.write-target=shadow)";
  }

  @Override
  public Optional<Map<String, String>> premiums(LocalDate businessDate) {
    List<Long> instance =
        jdbc.queryForList(LATEST_COMPLETED_RUN, Long.class, Date.valueOf(businessDate));
    if (instance.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> rows =
        JdbcPremiumOutputSource.read(jdbc, ROWS, ShadowPremiumWriter.shadowRunId(instance.get(0)));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
  }
}
