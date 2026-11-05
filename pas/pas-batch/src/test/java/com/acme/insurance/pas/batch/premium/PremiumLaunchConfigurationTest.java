package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PremiumLaunchConfigurationTest {

  final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(PremiumLaunchConfiguration.class)
          .withBean(JobLauncher.class, () -> mock(JobLauncher.class))
          .withBean(JobExplorer.class, () -> mock(JobExplorer.class))
          .withBean(JobRepository.class, () -> mock(JobRepository.class))
          .withBean(PremiumJobParameters.class, () -> mock(PremiumJobParameters.class));

  @Test
  void theApplicationRunnerIsThePremiumScheduledLauncher() {
    context.run(
        app ->
            assertThat(app.getBean(JobLauncherApplicationRunner.class))
                .isInstanceOf(PremiumScheduledLauncher.class));
  }

  @Test
  void noRunnerWhenJobsAreDisabledLikeInBoot() {
    context
        .withPropertyValues("spring.batch.job.enabled=false")
        .run(app -> assertThat(app).doesNotHaveBean(JobLauncherApplicationRunner.class));
  }
}
