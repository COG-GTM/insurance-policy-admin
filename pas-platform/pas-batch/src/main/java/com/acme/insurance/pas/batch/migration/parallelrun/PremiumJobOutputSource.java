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
 * pas_batch.premium_run_summary} with {@code write_target = 'SHADOW'} (a restart shares the job
 * instance; {@code rerun=true} creates a newer one). Its rows are {@code pas_shadow.premiums WHERE
 * shadow_run_id = ShadowPremiumWriter.shadowRunId(job_instance_id)}, possibly none for an empty
 * book. No completed shadow run (only {@code pas} runs or none), or a run with no rows that skipped
 * policies ({@code policy_errors > 0}), means the output is not available and the day is
 * INCOMPLETE.
 */
public final class PremiumJobOutputSource implements PremiumOutputSource {

  static final String LATEST_COMPLETED_RUN =
      "SELECT job_instance_id, policy_errors FROM pas_batch.premium_run_summary"
          + " WHERE run_date = ? AND batch_status = 'COMPLETED' AND write_target = 'SHADOW'"
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
    List<long[]> run =
        jdbc.query(
            LATEST_COMPLETED_RUN,
            (rs, i) -> new long[] {rs.getLong("job_instance_id"), rs.getLong("policy_errors")},
            Date.valueOf(businessDate));
    if (run.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> rows =
        JdbcPremiumOutputSource.read(jdbc, ROWS, ShadowPremiumWriter.shadowRunId(run.get(0)[0]));
    // No rows is an empty book only if no policy was skipped; otherwise the output is missing.
    if (rows.isEmpty() && run.get(0)[1] > 0) {
      return Optional.empty();
    }
    return Optional.of(rows);
  }
}
