package com.acme.insurance.pas.batch.premium;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PremiumRunListener implements JobExecutionListener {
  private static final Logger log = LoggerFactory.getLogger(PremiumRunListener.class);
  private final JdbcTemplate jdbcTemplate;
  private final MeterRegistry registry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring-managed JDBC and metrics registries are retained for run reporting.")
  public PremiumRunListener(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
    this.jdbcTemplate = jdbcTemplate;
    this.registry = registry;
  }

  @Override
  public void afterJob(JobExecution execution) {
    if ("DISABLED".equals(execution.getExitStatus().getExitCode())) {
      return;
    }
    long executionId = execution.getId();
    long instanceId = execution.getJobInstance().getInstanceId();
    LocalDate businessDate =
        LocalDate.parse(execution.getJobParameters().getString("businessDate"));
    String target = execution.getExecutionContext().getString("premiumWriteTarget", "PAS");
    Counts counts =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*), COUNT(*) FILTER (WHERE status = 'UPDATED'), "
                + "COUNT(*) FILTER (WHERE status = 'ERROR') "
                + "FROM pas_batch.premium_run_item WHERE job_instance_id = ?",
            (resultSet, rowNumber) ->
                new Counts(resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3)),
            instanceId);
    long skipped =
        execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().startsWith("premiumWorkerStep"))
            .mapToLong(org.springframework.batch.core.StepExecution::getSkipCount)
            .sum();
    long errors = counts == null ? 0 : counts.errors();
    if (errors > 0 && execution.getStatus() == BatchStatus.COMPLETED) {
      execution.setExitStatus(new ExitStatus("COMPLETED_WITH_ERRORS"));
    }
    var startTime = execution.getStartTime();
    OffsetDateTime started =
        startTime == null ? OffsetDateTime.now(ZoneOffset.UTC) : startTime.atOffset(ZoneOffset.UTC);
    var endTime = execution.getEndTime();
    OffsetDateTime completed =
        endTime == null ? OffsetDateTime.now(ZoneOffset.UTC) : endTime.atOffset(ZoneOffset.UTC);
    jdbcTemplate.update(
        "INSERT INTO pas_batch.premium_run_summary "
            + "(job_execution_id, job_instance_id, business_date, write_target, status, exit_code, "
            + "policies_read, policies_updated, policies_skipped, policies_error, started_at, completed_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (job_execution_id) DO UPDATE SET status = EXCLUDED.status, "
            + "exit_code = EXCLUDED.exit_code, policies_read = EXCLUDED.policies_read, "
            + "policies_updated = EXCLUDED.policies_updated, policies_skipped = EXCLUDED.policies_skipped, "
            + "policies_error = EXCLUDED.policies_error, completed_at = EXCLUDED.completed_at",
        executionId,
        instanceId,
        businessDate,
        target,
        execution.getStatus().name(),
        execution.getExitStatus().getExitCode(),
        counts == null ? 0 : counts.read(),
        counts == null ? 0 : counts.updated(),
        skipped,
        errors,
        started,
        completed);
    log.atInfo()
        .addKeyValue("event", "pas.batch.premium.run.summary")
        .addKeyValue("job", execution.getJobInstance().getJobName())
        .addKeyValue("jobExecutionId", executionId)
        .addKeyValue("businessDate", businessDate)
        .addKeyValue("writeTarget", target)
        .addKeyValue("status", execution.getStatus().name())
        .addKeyValue("read", counts == null ? 0 : counts.read())
        .addKeyValue("updated", counts == null ? 0 : counts.updated())
        .addKeyValue("skipped", skipped)
        .addKeyValue("errors", errors)
        .log("Premium batch run summary");
    if (errors > 0) {
      registry.counter("pas.batch.premium.run.errors").increment(errors);
    }
    if (execution.getStatus() == BatchStatus.FAILED || errors > 0) {
      log.atError()
          .addKeyValue("event", "alert")
          .addKeyValue("alert", "PREMIUM_BATCH_FAILED")
          .addKeyValue("jobExecutionId", executionId)
          .addKeyValue("businessDate", businessDate)
          .addKeyValue("status", execution.getStatus().name())
          .addKeyValue("exitCode", execution.getExitStatus().getExitCode())
          .addKeyValue("errors", errors)
          .log("Premium batch requires operations attention");
    }
  }

  private record Counts(long read, long updated, long errors) {}
}
