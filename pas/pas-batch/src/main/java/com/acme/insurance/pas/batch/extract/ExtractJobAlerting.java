package com.acme.insurance.pas.batch.extract;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Raises the on-call alert for a failed extract job: an ERROR log event {@code pas.extract.alert}
 * and the counter {@code pas.extract.job.failures}, both routed by the platform alert rules. The
 * job also exits non-zero, so the scheduler marks the run failed. No record content is logged.
 */
public class ExtractJobAlerting implements JobExecutionListener {

  private static final Logger log = LoggerFactory.getLogger(ExtractJobAlerting.class);
  private final MeterRegistry meters;

  public ExtractJobAlerting(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void afterJob(JobExecution execution) {
    String job = execution.getJobInstance().getJobName();
    String businessDate =
        execution.getJobParameters().getString(BusinessDateParameters.BUSINESS_DATE);
    if (execution.getStatus() == BatchStatus.COMPLETED) {
      meters.counter("pas.extract.job.success", "job", job).increment();
      log.info("extract job completed job={} businessDate={}", job, businessDate);
      return;
    }
    meters.counter("pas.extract.job.failures", "job", job).increment();
    String reasons =
        execution.getAllFailureExceptions().stream()
            .map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
            .toList()
            .toString();
    log.error(
        "pas.extract.alert job={} businessDate={} status={} reasons={}",
        job,
        businessDate,
        execution.getStatus(),
        reasons);
  }
}
