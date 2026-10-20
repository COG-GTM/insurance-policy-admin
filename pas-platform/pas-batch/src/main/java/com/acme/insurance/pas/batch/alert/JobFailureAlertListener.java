package com.acme.insurance.pas.batch.alert;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Counts every run ({@code pas.batch.job.runs{job,status}}) and raises a {@link BatchAlert} to all
 * notifiers when a job does not complete. A failing notifier never masks the others.
 */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "shares Spring-managed collaborators (MeterRegistry, JDBC, properties) by design")
public class JobFailureAlertListener implements JobExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger(JobFailureAlertListener.class);

  private final List<BatchAlertNotifier> notifiers;
  private final MeterRegistry meters;

  public JobFailureAlertListener(List<BatchAlertNotifier> notifiers, MeterRegistry meters) {
    this.notifiers = List.copyOf(notifiers);
    this.meters = meters;
  }

  @Override
  public void afterJob(JobExecution execution) {
    String job = execution.getJobInstance().getJobName();
    BatchStatus status = execution.getStatus();
    meters.counter("pas.batch.job.runs", "job", job, "status", status.name()).increment();
    if (status == BatchStatus.COMPLETED) {
      return;
    }
    Map<String, String> parameters = new LinkedHashMap<>();
    execution
        .getJobParameters()
        .getParameters()
        .forEach((k, v) -> parameters.put(k, String.valueOf(v.getValue())));
    List<String> failures =
        execution.getAllFailureExceptions().stream().map(Throwable::toString).toList();
    BatchAlert alert =
        new BatchAlert(
            job,
            execution.getId(),
            status.name(),
            execution.getExitStatus().getExitCode(),
            parameters,
            failures);
    for (BatchAlertNotifier notifier : notifiers) {
      try {
        notifier.notify(alert);
      } catch (RuntimeException e) {
        LOG.error("alert notifier {} failed", notifier.getClass().getName(), e);
      }
    }
  }
}
