package com.acme.insurance.pas.batch.extract.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.insurance.pas.batch.extract.routing.ExtractRouteProperties;
import com.acme.insurance.pas.batch.extract.routing.RouteMode;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

class ExtractCliRunnerTest {
  @Test
  void returnsNonzeroWhenTheRetiredCapabilityDoesNotComplete() throws Exception {
    JobLauncher launcher = mock(JobLauncher.class);
    Job dailyJob = mock(Job.class);
    Job monthlyJob = mock(Job.class);
    JobExecution execution = mock(JobExecution.class);
    when(execution.getStatus()).thenReturn(BatchStatus.FAILED);
    when(launcher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    ExtractCliRunner runner =
        new ExtractCliRunner(
            launcher,
            dailyJob,
            monthlyJob,
            new ExtractRouteProperties(RouteMode.RETIRED, RouteMode.LEGACY),
            "dailyExtractJob",
            "2025-02-10",
            "1",
            "America/New_York");

    runner.run();

    assertThat(runner.getExitCode()).isEqualTo(1);
  }
}
