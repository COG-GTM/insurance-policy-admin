package com.acme.insurance.pas.batch.premium;

import java.util.Optional;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Spring Boot's job launcher for {@code java -jar pas-batch-exec.jar} (the CronJob and its pod
 * retry), with one addition: a launch of {@code premiumRecalculation} without arguments restarts
 * today's latest execution when it is {@code FAILED} or {@code STOPPED} ({@link
 * PremiumJobParameters#restartOfToday()}), from the last committed chunk of every partition. Every
 * other launch, including any launch of another job, is Boot's unchanged.
 */
public class PremiumScheduledLauncher extends JobLauncherApplicationRunner {

  private final JobLauncher launcher;
  private final PremiumJobParameters parameters;
  private ApplicationEventPublisher publisher;

  public PremiumScheduledLauncher(
      JobLauncher launcher,
      JobExplorer explorer,
      JobRepository repository,
      PremiumJobParameters parameters) {
    super(launcher, explorer, repository);
    this.launcher = launcher;
    this.parameters = parameters;
  }

  @Override
  public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
    super.setApplicationEventPublisher(publisher);
    this.publisher = publisher;
  }

  @Override
  protected void execute(Job job, JobParameters jobParameters)
      throws JobExecutionAlreadyRunningException,
          JobRestartException,
          JobInstanceAlreadyCompleteException,
          JobParametersInvalidException {
    Optional<JobParameters> restart =
        PremiumJobConfiguration.JOB.equals(job.getName()) && jobParameters.isEmpty()
            ? parameters.restartOfToday()
            : Optional.empty();
    if (restart.isEmpty()) {
      super.execute(job, jobParameters);
      return;
    }
    JobExecution execution = launcher.run(job, restart.get());
    if (publisher != null) {
      publisher.publishEvent(new JobExecutionEvent(execution));
    }
  }
}
