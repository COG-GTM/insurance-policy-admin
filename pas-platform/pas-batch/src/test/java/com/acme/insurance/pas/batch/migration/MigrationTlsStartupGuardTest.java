package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

class MigrationTlsStartupGuardTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

  @Test
  void rejectsDisabledSslOutsideLocalProfiles() {
    contextRunner
        .withPropertyValues("spring.profiles.active=prod")
        .withBean(MigrationProperties.class, () -> properties(false, "/truststore"))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "pas.migration.source.ssl must be true outside local/test profiles"));
  }

  @Test
  void rejectsMissingTruststoreOutsideLocalProfiles() {
    contextRunner
        .withPropertyValues("spring.profiles.active=prod")
        .withBean(MigrationProperties.class, () -> properties(true, ""))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "pas.migration.source.ssl-trust-store-location is required outside local/test profiles"));
  }

  @Test
  void allowsLocalProfileWithoutSslOrTruststore() {
    contextRunner
        .withPropertyValues("spring.profiles.active=local")
        .withBean(MigrationProperties.class, () -> properties(false, ""))
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void allowsIntegrationTestProfileWithoutSslOrTruststore() {
    contextRunner
        .withPropertyValues("spring.profiles.active=it")
        .withBean(MigrationProperties.class, () -> properties(false, ""))
        .run(context -> assertThat(context).hasNotFailed());
  }

  private static MigrationProperties properties(boolean ssl, String trustStore) {
    return new MigrationProperties(
        true,
        new MigrationProperties.Source(
            "jdbc:db2://host:50000/acmeins",
            "user",
            "password",
            "ACMEINS",
            ssl,
            trustStore,
            "UTC",
            100),
        null,
        null,
        null,
        null,
        null);
  }

  @Configuration(proxyBeanMethods = false)
  static class TestConfiguration {
    @Bean
    MigrationTlsStartupGuard guard(MigrationProperties properties, Environment environment) {
      return new MigrationTlsStartupGuard(properties, environment);
    }
  }
}
