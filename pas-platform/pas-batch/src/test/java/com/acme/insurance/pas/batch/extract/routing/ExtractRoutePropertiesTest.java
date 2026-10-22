package com.acme.insurance.pas.batch.extract.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ExtractRoutePropertiesTest {
  @Test
  void defaultsMissingRouteModesToLegacy() {
    ExtractRouteProperties properties = new ExtractRouteProperties(null, null);

    assertThat(properties.dailyExtract()).isEqualTo(RouteMode.LEGACY);
    assertThat(properties.monthlyExposure()).isEqualTo(RouteMode.LEGACY);
  }

  @Test
  void resolvesRouteByJobName() {
    ExtractRouteProperties properties =
        new ExtractRouteProperties(RouteMode.NEW, RouteMode.RETIRED);

    assertThat(properties.forJob("dailyExtractJob")).isEqualTo(RouteMode.NEW);
    assertThat(properties.forJob("monthlyExposureJob")).isEqualTo(RouteMode.RETIRED);
  }

  @Test
  void bindsConfiguredModesAndDefaultsTheOtherRoute() {
    new ApplicationContextRunner()
        .withUserConfiguration(RoutePropertiesConfiguration.class)
        .withPropertyValues("pas.routing.daily-extract=new")
        .run(
            context -> {
              ExtractRouteProperties properties = context.getBean(ExtractRouteProperties.class);
              assertThat(properties.dailyExtract()).isEqualTo(RouteMode.NEW);
              assertThat(properties.monthlyExposure()).isEqualTo(RouteMode.LEGACY);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ExtractRouteProperties.class)
  static class RoutePropertiesConfiguration {}
}
