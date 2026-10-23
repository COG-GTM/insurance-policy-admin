package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.acme.insurance.pas.common.routing.RouteFlags;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PremiumSchedulingConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              PremiumSchedulingConfiguration.class, SchedulingDependencies.class);

  @Test
  void schedulerIsAbsentByDefault() {
    contextRunner.run(
        context -> assertThat(context).doesNotHaveBean(PremiumSchedulingConfiguration.class));
  }

  @Test
  void schedulerIsPresentWhenEnabled() {
    contextRunner
        .withPropertyValues("pas.batch.premium.scheduler-enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(PremiumSchedulingConfiguration.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class SchedulingDependencies {
    @Bean
    JobLauncher jobLauncher() {
      return mock(JobLauncher.class);
    }

    @Bean
    Job premiumRecalculationJob() {
      return mock(Job.class);
    }

    @Bean
    RouteFlags routeFlags() {
      return RouteFlags.of(Map.of());
    }
  }
}
