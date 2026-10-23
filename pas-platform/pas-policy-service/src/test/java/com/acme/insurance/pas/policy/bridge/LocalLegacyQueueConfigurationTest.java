package com.acme.insurance.pas.policy.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalLegacyQueueConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(LocalLegacyQueueConfiguration.class);

  @Test
  void providesQueueSimulatorOnlyForLocalProfile() {
    contextRunner
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(LegacyQueuePort.class);
              context.getBean(LegacyQueuePort.class).put("local-test", new byte[4], Map.of());
            });

    contextRunner
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
        .run(context -> assertThat(context).doesNotHaveBean(LegacyQueuePort.class));
  }
}
