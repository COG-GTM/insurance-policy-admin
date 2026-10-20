package com.acme.insurance.pas.batch.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersBuilder;

class JobFailureAlertListenerTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final List<BatchAlert> alerts = new ArrayList<>();

  private JobExecution execution(BatchStatus status) {
    JobExecution execution =
        new JobExecution(
            new JobInstance(1L, "dailyExtractJob"),
            7L,
            new JobParametersBuilder().addString("businessDate", "2026-10-16").toJobParameters());
    execution.setStatus(status);
    execution.setExitStatus(
        status == BatchStatus.COMPLETED ? ExitStatus.COMPLETED : ExitStatus.FAILED);
    return execution;
  }

  @Test
  void failedJobAlertsEveryNotifierEvenIfOneThrows() {
    BatchAlertNotifier broken =
        a -> {
          throw new IllegalStateException("pager down");
        };
    JobFailureAlertListener listener =
        new JobFailureAlertListener(List.of(broken, alerts::add), meters);
    JobExecution execution = execution(BatchStatus.FAILED);
    execution.addFailureException(new RuntimeException("sftp unreachable"));

    listener.afterJob(execution);

    assertThat(alerts)
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.jobName()).isEqualTo("dailyExtractJob");
              assertThat(a.parameters()).containsEntry("businessDate", "2026-10-16");
              assertThat(a.failures()).anyMatch(f -> f.contains("sftp unreachable"));
            });
    assertThat(
            meters
                .counter("pas.batch.job.runs", "job", "dailyExtractJob", "status", "FAILED")
                .count())
        .isEqualTo(1);
  }

  @Test
  void completedJobDoesNotAlert() {
    new JobFailureAlertListener(List.of(alerts::add), meters)
        .afterJob(execution(BatchStatus.COMPLETED));
    assertThat(alerts).isEmpty();
  }
}
