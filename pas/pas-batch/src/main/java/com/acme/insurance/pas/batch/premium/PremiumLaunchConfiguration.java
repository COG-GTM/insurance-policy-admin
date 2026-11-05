package com.acme.insurance.pas.batch.premium;

import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Replaces Spring Boot's {@link JobLauncherApplicationRunner} (same condition, same job name
 * property) with {@link PremiumScheduledLauncher}, so the CronJob's pod retry restarts today's
 * failed premium run. Boot backs off because the bean type is already defined.
 */
@Configuration(proxyBeanMethods = false)
public class PremiumLaunchConfiguration {

  @Bean
  @ConditionalOnBooleanProperty(name = "spring.batch.job.enabled", matchIfMissing = true)
  JobLauncherApplicationRunner jobLauncherApplicationRunner(
      JobLauncher launcher,
      JobExplorer explorer,
      JobRepository repository,
      PremiumJobParameters premiumJobParameters,
      @Value("${spring.batch.job.name:}") String jobName) {
    PremiumScheduledLauncher runner =
        new PremiumScheduledLauncher(launcher, explorer, repository, premiumJobParameters);
    if (StringUtils.hasText(jobName)) {
      runner.setJobName(jobName);
    }
    return runner;
  }
}
