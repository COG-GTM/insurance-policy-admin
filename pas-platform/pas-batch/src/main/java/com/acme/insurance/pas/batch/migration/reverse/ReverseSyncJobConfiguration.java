package com.acme.insurance.pas.batch.migration.reverse;

import com.acme.insurance.pas.batch.migration.MigrationProperties;
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
public class ReverseSyncJobConfiguration {
  @Bean
  Step reverseSyncStep(
      JobRepository repository,
      @Qualifier("migrationTransactionManager")
          PlatformTransactionManager migrationTransactionManager,
      MigrationProperties properties,
      ReverseSyncService reverseSyncService) {
    return new StepBuilder("reverseSyncStep", repository)
        .tasklet(
            (contribution, chunkContext) -> {
              if (!properties.reverseSync().enabled()) {
                contribution.setExitStatus(
                    new org.springframework.batch.core.ExitStatus("DISABLED"));
              } else {
                Object drainParameter =
                    chunkContext.getStepContext().getJobParameters().get("drain");
                boolean drain =
                    drainParameter == null || Boolean.parseBoolean(drainParameter.toString());
                int applied = drain ? reverseSyncService.drain() : reverseSyncService.syncOnce();
                contribution
                    .getStepExecution()
                    .getExecutionContext()
                    .putInt("reverseSyncRowsApplied", applied);
              }
              return org.springframework.batch.repeat.RepeatStatus.FINISHED;
            },
            migrationTransactionManager)
        .build();
  }

  @Bean
  Step pruneChangeLogStep(
      JobRepository repository,
      @Qualifier("migrationTransactionManager")
          PlatformTransactionManager migrationTransactionManager,
      MigrationProperties properties,
      ReverseSyncService reverseSyncService) {
    return new StepBuilder("pruneChangeLogStep", repository)
        .tasklet(
            (contribution, chunkContext) -> {
              if (!properties.reverseSync().enabled()) {
                contribution.setExitStatus(
                    new org.springframework.batch.core.ExitStatus("DISABLED"));
              } else {
                int pruned = reverseSyncService.pruneChangeLog();
                contribution
                    .getStepExecution()
                    .getExecutionContext()
                    .putInt("reverseSyncChangeLogRowsPruned", pruned);
              }
              return org.springframework.batch.repeat.RepeatStatus.FINISHED;
            },
            migrationTransactionManager)
        .build();
  }

  @Bean
  Job reverseSyncJob(
      JobRepository repository,
      @Qualifier("reverseSyncStep") Step reverseSyncStep,
      @Qualifier("pruneChangeLogStep") Step pruneChangeLogStep,
      JobFailureListener migrationJobFailureListener) {
    return new JobBuilder("reverseSyncJob", repository)
        .start(reverseSyncStep)
        .next(pruneChangeLogStep)
        .listener(migrationJobFailureListener)
        .build();
  }
}
