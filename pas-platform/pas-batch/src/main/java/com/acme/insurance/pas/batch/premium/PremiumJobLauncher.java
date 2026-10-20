package com.acme.insurance.pas.batch.premium;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Component;

/** Single entry point used by the scheduler, the REST API and the CLI to start/restart runs. */
@Component
public final class PremiumJobLauncher {

  private static final Set<BatchStatus> RESTARTABLE =
      Set.of(BatchStatus.FAILED, BatchStatus.STOPPED);

  private final Job job;
  private final JobExplorer explorer;
  private final JobLauncher syncLauncher;
  private final TaskExecutorJobLauncher asyncLauncher;

  public PremiumJobLauncher(
      @Qualifier(PremiumRecalculationJobConfig.JOB_NAME) Job job,
      JobRepository jobRepository,
      JobExplorer explorer,
      JobLauncher jobLauncher)
      throws Exception {
    this.job = job;
    this.explorer = explorer;
    this.syncLauncher = jobLauncher;
    this.asyncLauncher = new TaskExecutorJobLauncher();
    asyncLauncher.setJobRepository(jobRepository);
    asyncLauncher.setTaskExecutor(new SimpleAsyncTaskExecutor("premium-launch-"));
    asyncLauncher.afterPropertiesSet();
  }

  public static JobParameters parameters(LocalDate runDate, boolean rerun) {
    JobParametersBuilder params =
        new JobParametersBuilder().addLocalDate(PremiumRecalculationJobConfig.RUN_DATE, runDate);
    if (rerun) {
      params.addLong(PremiumRecalculationJobConfig.RERUN, System.currentTimeMillis());
    }
    return params.toJobParameters();
  }

  /**
   * Starts the run for {@code runDate}. If that date's last run failed this restarts it; if it
   * already completed, a plain start is rejected and {@code rerun=true} recalculates it.
   */
  public JobExecution start(LocalDate runDate, boolean rerun, boolean async)
      throws JobInstanceAlreadyCompleteException,
          JobExecutionAlreadyRunningException,
          JobRestartException {
    return launch(parameters(runDate, rerun), async);
  }

  /** Restarts a FAILED/STOPPED execution with its original parameters. */
  public JobExecution restart(long jobExecutionId, boolean async)
      throws JobInstanceAlreadyCompleteException,
          JobExecutionAlreadyRunningException,
          JobRestartException {
    JobExecution previous =
        find(jobExecutionId)
            .orElseThrow(
                () -> new JobRestartException("no premium job execution " + jobExecutionId));
    if (!RESTARTABLE.contains(previous.getStatus())) {
      throw new JobRestartException(
          "job execution " + jobExecutionId + " is " + previous.getStatus() + ", not restartable");
    }
    return launch(previous.getJobParameters(), async);
  }

  /** Last execution of the plain (non-rerun) run for {@code runDate}, if any. */
  public Optional<JobExecution> lastExecution(LocalDate runDate) {
    return Optional.ofNullable(
            explorer.getJobInstance(
                PremiumRecalculationJobConfig.JOB_NAME, parameters(runDate, false)))
        .map(explorer::getLastJobExecution);
  }

  public Optional<JobExecution> find(long jobExecutionId) {
    return Optional.ofNullable(explorer.getJobExecution(jobExecutionId))
        .filter(
            e -> PremiumRecalculationJobConfig.JOB_NAME.equals(e.getJobInstance().getJobName()));
  }

  private JobExecution launch(JobParameters params, boolean async)
      throws JobInstanceAlreadyCompleteException,
          JobExecutionAlreadyRunningException,
          JobRestartException {
    try {
      return (async ? asyncLauncher : syncLauncher).run(job, params);
    } catch (org.springframework.batch.core.JobParametersInvalidException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }
}
