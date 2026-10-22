package com.acme.insurance.pas.batch.platform;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

public final class JobFailureListener implements JobExecutionListener {
  private static final Logger log = LoggerFactory.getLogger(JobFailureListener.class);
  private final MeterRegistry registry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The Spring-managed meter registry is retained for recording job failures.")
  public JobFailureListener(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void afterJob(JobExecution execution) {
    if (execution.getStatus() == BatchStatus.COMPLETED) {
      return;
    }
    String jobName = execution.getJobInstance().getJobName();
    registry.counter("pas.batch.job.failures", "job", jobName).increment();
    String failureTypes =
        execution.getAllFailureExceptions().stream()
            .map(exception -> exception.getClass().getSimpleName())
            .distinct()
            .sorted()
            .collect(Collectors.joining(","));
    log.atError()
        .addKeyValue("event", "pas.batch.job.failed")
        .addKeyValue("job", jobName)
        .addKeyValue("jobExecutionId", execution.getId())
        .addKeyValue("businessDate", execution.getJobParameters().getString("businessDate"))
        .addKeyValue("exitCode", execution.getExitStatus().getExitCode())
        .addKeyValue("failureType", failureTypes)
        .log("Batch job failed");
  }
}
