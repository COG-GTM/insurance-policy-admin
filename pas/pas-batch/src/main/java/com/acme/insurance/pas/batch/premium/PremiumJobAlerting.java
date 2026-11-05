package com.acme.insurance.pas.batch.premium;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.item.ExecutionContext;

/**
 * Replaces PREMBAT's TSO SEND to PASADMIN with a truthful status (Q-28): a failed run logs {@code
 * pas.premium.alert} at ERROR and exits non-zero (the Kubernetes Job fails and pages); a run that
 * completed with policy errors logs {@code pas.premium.warning} and finishes with exit status
 * {@code COMPLETED_WITH_ERRORS}; a clean run logs the summary only.
 */
public class PremiumJobAlerting implements JobExecutionListener {

  public static final String COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS";

  private static final Logger log = LoggerFactory.getLogger(PremiumJobAlerting.class);

  private final MeterRegistry meters;

  public PremiumJobAlerting(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void afterJob(JobExecution execution) {
    String businessDate =
        execution.getJobParameters().getString(PremiumJobParameters.BUSINESS_DATE);
    ExecutionContext context = execution.getExecutionContext();
    long read = context.getLong(PremiumJobConfiguration.READ, -1);
    long updated = context.getLong(PremiumJobConfiguration.UPDATED, -1);
    long errors = context.getLong(PremiumJobConfiguration.ERRORS, -1);
    if (execution.getStatus() != BatchStatus.COMPLETED) {
      meters.counter("pas.premium.job.failures").increment();
      List<String> reasons =
          execution.getAllFailureExceptions().stream()
              .map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
              .toList();
      log.error(
          "pas.premium.alert job={} businessDate={} status={} reasons={}",
          PremiumJobConfiguration.JOB,
          businessDate,
          execution.getStatus(),
          reasons);
      return;
    }
    if (errors > 0) {
      meters.counter("pas.premium.job.warnings").increment();
      execution.setExitStatus(
          new ExitStatus(COMPLETED_WITH_ERRORS, errors + " of " + read + " policies failed"));
      log.warn(
          "pas.premium.warning job={} businessDate={} read={} updated={} errors={}",
          PremiumJobConfiguration.JOB,
          businessDate,
          read,
          updated,
          errors);
      return;
    }
    meters.counter("pas.premium.job.success").increment();
    log.info(
        "premium recalculation completed job={} businessDate={} read={} updated={} errors={}",
        PremiumJobConfiguration.JOB,
        businessDate,
        read,
        updated,
        errors);
  }
}
