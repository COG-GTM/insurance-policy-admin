package com.acme.insurance.pas.batch.migration.delta;

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
public class DeltaSyncJobConfiguration {
  @Bean
  Step deltaSyncStep(
      JobRepository repository,
      @Qualifier("migrationTransactionManager")
          PlatformTransactionManager migrationTransactionManager,
      DeltaSyncService deltaSyncService,
      MigrationProperties properties) {
    return new StepBuilder("deltaSyncStep", repository)
        .tasklet(
            (contribution, chunkContext) -> {
              if (properties.deltaSync().paused()) {
                contribution.setExitStatus(
                    new org.springframework.batch.core.ExitStatus("DISABLED"));
              } else {
                deltaSyncService.sync();
              }
              return org.springframework.batch.repeat.RepeatStatus.FINISHED;
            },
            migrationTransactionManager)
        .build();
  }

  @Bean
  Job deltaSyncJob(
      JobRepository repository,
      Step deltaSyncStep,
      JobFailureListener migrationJobFailureListener) {
    return new JobBuilder("deltaSyncJob", repository)
        .start(deltaSyncStep)
        .listener(migrationJobFailureListener)
        .build();
  }
}
