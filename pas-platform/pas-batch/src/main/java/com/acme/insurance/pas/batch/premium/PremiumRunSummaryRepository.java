package com.acme.insurance.pas.batch.premium;

import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code pas_batch.premium_run_summary}: one row per job execution (restarts get their own row).
 */
public class PremiumRunSummaryRepository {

  private static final String UPSERT_SQL =
      """
      INSERT INTO pas_batch.premium_run_summary (
          job_execution_id, job_instance_id, run_date, batch_status, exit_code, outcome,
          policies_read, policies_updated, policy_errors, rating_version, report_path,
          started_at, ended_at, failure_message)
      VALUES (
          :jobExecutionId, :jobInstanceId, :runDate, :batchStatus, :exitCode, :outcome,
          :read, :updated, :errors, :ratingVersion, :reportPath,
          :startedAt, :endedAt, :failureMessage)
      ON CONFLICT (job_execution_id) DO UPDATE SET
          batch_status = EXCLUDED.batch_status,
          exit_code = EXCLUDED.exit_code,
          outcome = EXCLUDED.outcome,
          policies_read = EXCLUDED.policies_read,
          policies_updated = EXCLUDED.policies_updated,
          policy_errors = EXCLUDED.policy_errors,
          rating_version = EXCLUDED.rating_version,
          report_path = EXCLUDED.report_path,
          started_at = EXCLUDED.started_at,
          ended_at = EXCLUDED.ended_at,
          failure_message = EXCLUDED.failure_message,
          recorded_at = LOCALTIMESTAMP
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public PremiumRunSummaryRepository(DataSource dataSource) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  public void save(PremiumRunSummary s) {
    jdbc.update(
        UPSERT_SQL,
        new MapSqlParameterSource()
            .addValue("jobExecutionId", s.jobExecutionId())
            .addValue("jobInstanceId", s.jobInstanceId())
            .addValue("runDate", s.runDate())
            .addValue("batchStatus", s.batchStatus())
            .addValue("exitCode", s.exitCode())
            .addValue("outcome", s.outcome().name())
            .addValue("read", s.totals().read())
            .addValue("updated", s.totals().updated())
            .addValue("errors", s.totals().errors())
            .addValue("ratingVersion", s.ratingVersion())
            .addValue("reportPath", s.reportPath())
            .addValue("startedAt", s.startedAt())
            .addValue("endedAt", s.endedAt())
            .addValue("failureMessage", s.failureMessage()));
  }

  public Optional<PremiumRunSummary> find(long jobExecutionId) {
    return jdbc
        .query(
            "SELECT * FROM pas_batch.premium_run_summary WHERE job_execution_id = :id",
            new MapSqlParameterSource("id", jobExecutionId),
            (rs, i) ->
                new PremiumRunSummary(
                    rs.getLong("job_execution_id"),
                    rs.getLong("job_instance_id"),
                    rs.getObject("run_date", java.time.LocalDate.class),
                    rs.getString("batch_status"),
                    rs.getString("exit_code"),
                    PremiumRunOutcome.valueOf(rs.getString("outcome")),
                    new PremiumRunTotals(
                        rs.getLong("policies_read"),
                        rs.getLong("policies_updated"),
                        rs.getLong("policy_errors")),
                    rs.getString("rating_version"),
                    rs.getString("report_path"),
                    rs.getObject("started_at", java.time.LocalDateTime.class),
                    rs.getObject("ended_at", java.time.LocalDateTime.class),
                    rs.getString("failure_message")))
        .stream()
        .findFirst();
  }
}
