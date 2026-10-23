package com.acme.insurance.pas.batch.migration.recon;

import com.acme.insurance.pas.batch.platform.JobFailureListener;
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
public class ReconciliationJobConfiguration {
  @Bean
  Step reconciliationStep(
      JobRepository repository,
      @Qualifier("migrationTransactionManager")
          PlatformTransactionManager migrationTransactionManager,
      ReconciliationService reconciliationService) {
    return new StepBuilder("reconciliationStep", repository)
        .tasklet(
            (contribution, chunkContext) -> {
              ReconciliationService.Result result = reconciliationService.reconcile();
              if (!result.status().equals("CLEAN")) {
                contribution.setExitStatus(
                    new org.springframework.batch.core.ExitStatus("COMPLETED_WITH_DIFFERENCES"));
              }
              contribution
                  .getStepExecution()
                  .getExecutionContext()
                  .putLong("reconRunId", result.runId());
              return org.springframework.batch.repeat.RepeatStatus.FINISHED;
            },
            migrationTransactionManager)
        .build();
  }

  @Bean
  Job reconciliationJob(
      JobRepository repository,
      Step reconciliationStep,
      JobFailureListener migrationJobFailureListener) {
    return new JobBuilder("reconciliationJob", repository)
        .start(reconciliationStep)
        .listener(migrationJobFailureListener)
        .build();
  }
}
