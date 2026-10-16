package com.acme.insurance.pas.batch.placeholder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Placeholder job proving the batch runtime and metadata schema work end to end. */
@Configuration
public class NoopJobConfig {

  private static final Logger LOG = LoggerFactory.getLogger(NoopJobConfig.class);

  @Bean
  public Tasklet noopTasklet() {
    return (contribution, chunkContext) -> {
      LOG.info("noopJob tasklet executed (placeholder for AB-519 batch waves)");
      return RepeatStatus.FINISHED;
    };
  }

  @Bean
  public Step noopStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
    return new StepBuilder("noopStep", jobRepository)
        .tasklet(noopTasklet(), transactionManager)
        .build();
  }

  @Bean
  public Job noopJob(JobRepository jobRepository, Step noopStep) {
    return new JobBuilder("noopJob", jobRepository).start(noopStep).build();
  }
}
