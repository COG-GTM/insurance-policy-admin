package com.acme.insurance.pas.batch.migration.parallel;

import com.acme.insurance.pas.batch.platform.JobFailureListener;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
public class ParallelRunJobConfiguration {
  @Bean
  Step parallelRunDiffStep(
      JobRepository repository,
      @Qualifier("migrationTransactionManager")
          PlatformTransactionManager migrationTransactionManager,
      ParallelRunService parallelRunService) {
    return new StepBuilder("parallelRunDiffStep", repository)
        .tasklet(
            (contribution, chunkContext) -> {
              var parameters = chunkContext.getStepContext().getJobParameters();
              LocalDate date = LocalDate.parse((String) parameters.get("businessDate"));
              String cycleValue = (String) parameters.get("cycleId");
              UUID cycleId =
                  cycleValue == null || cycleValue.isBlank() ? null : UUID.fromString(cycleValue);
              ParallelRunService.Result result = parallelRunService.compare(date, cycleId);
              if (result.differenceCount() > 0) {
                contribution.setExitStatus(new ExitStatus("COMPLETED_WITH_DIFFERENCES"));
              }
              return org.springframework.batch.repeat.RepeatStatus.FINISHED;
            },
            migrationTransactionManager)
        .build();
  }

  @Bean
  Job parallelRunDiffJob(
      JobRepository repository,
      Step parallelRunDiffStep,
      JobFailureListener migrationJobFailureListener) {
    return new JobBuilder("parallelRunDiffJob", repository)
        .start(parallelRunDiffStep)
        .listener(migrationJobFailureListener)
        .build();
  }
}
