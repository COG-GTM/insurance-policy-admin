package com.acme.insurance.pas.batch.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.insurance.pas.common.routing.RouteFlags;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

class BatchCliRunnerTest {
  @Test
  void returnsZeroForCompletedJob() throws Exception {
    JobLauncher launcher = mock(JobLauncher.class);
    Job job = mock(Job.class);
    JobExecution execution = completed(ExitStatus.COMPLETED);
    when(launcher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    BatchCliRunner runner = runner(launcher, Map.of("dailyExtractJob", job), "dailyExtractJob");

    runner.run();

    assertThat(runner.getExitCode()).isZero();
  }

  @Test
  void returnsFourForPremiumCompletionWithErrors() throws Exception {
    JobLauncher launcher = mock(JobLauncher.class);
    Job job = mock(Job.class);
    JobExecution execution = completed(new ExitStatus("COMPLETED_WITH_ERRORS"));
    when(launcher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    BatchCliRunner runner =
        runner(launcher, Map.of("premiumRecalculationJob", job), "premiumRecalculationJob");

    runner.run();

    assertThat(runner.getExitCode()).isEqualTo(4);
  }

  @Test
  void returnsThreeForLegacyPremiumRoute() throws Exception {
    JobLauncher launcher = mock(JobLauncher.class);
    Job job = mock(Job.class);
    JobExecution execution = completed(new ExitStatus("DISABLED"));
    when(launcher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    BatchCliRunner runner =
        runner(launcher, Map.of("premiumRecalculationJob", job), "premiumRecalculationJob");

    runner.run();

    assertThat(runner.getExitCode()).isEqualTo(3);
  }

  @Test
  void returnsOneForFailedJob() throws Exception {
    JobLauncher launcher = mock(JobLauncher.class);
    Job job = mock(Job.class);
    JobExecution execution = mock(JobExecution.class);
    when(execution.getStatus()).thenReturn(BatchStatus.FAILED);
    when(execution.getExitStatus()).thenReturn(ExitStatus.FAILED);
    when(launcher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    BatchCliRunner runner = runner(launcher, Map.of("dailyExtractJob", job), "dailyExtractJob");

    runner.run();

    assertThat(runner.getExitCode()).isEqualTo(1);
  }

  @Test
  void returnsTwoForUnknownJobWithoutLaunching() throws Exception {
    JobLauncher launcher = mock(JobLauncher.class);
    BatchCliRunner runner = runner(launcher, Map.of(), "unknownJob");

    runner.run();

    assertThat(runner.getExitCode()).isEqualTo(2);
    verify(launcher, never()).run(any(Job.class), any(JobParameters.class));
  }

  @Test
  void returnsOneForRetiredExtractRoute() throws Exception {
    JobLauncher launcher = mock(JobLauncher.class);
    Job job = mock(Job.class);
    JobExecution execution = completed(ExitStatus.COMPLETED);
    when(launcher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);
    BatchCliRunner runner =
        new BatchCliRunner(
            launcher,
            Map.of("dailyExtractJob", job),
            RouteFlags.of(Map.of("daily-extract", "retired")),
            "dailyExtractJob",
            "2025-02-10",
            "",
            "America/New_York",
            "America/New_York");

    runner.run();

    assertThat(runner.getExitCode()).isEqualTo(1);
  }

  @Test
  void returnsOneWhenLaunchThrows() throws Exception {
    JobLauncher launcher = mock(JobLauncher.class);
    Job job = mock(Job.class);
    when(launcher.run(any(Job.class), any(JobParameters.class)))
        .thenThrow(new IllegalStateException("launch failed"));
    BatchCliRunner runner = runner(launcher, Map.of("dailyExtractJob", job), "dailyExtractJob");

    runner.run();

    assertThat(runner.getExitCode()).isEqualTo(1);
  }

  private static BatchCliRunner runner(
      JobLauncher launcher, Map<String, Job> jobs, String jobName) {
    return new BatchCliRunner(
        launcher,
        jobs,
        RouteFlags.of(Map.of("daily-extract", "new")),
        jobName,
        "2025-02-10",
        "1",
        "America/New_York",
        "America/New_York");
  }

  private static JobExecution completed(ExitStatus exitStatus) {
    JobExecution execution = mock(JobExecution.class);
    when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
    when(execution.getExitStatus()).thenReturn(exitStatus);
    return execution;
  }
}
