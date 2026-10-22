package com.acme.insurance.pas.batch.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BatchRouteConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(BatchRouteConfiguration.class);

  @Test
  void defaultsMissingRoutesToLegacy() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(RouteFlags.class).state(RouteFlags.PREMIUM_BATCH))
              .isEqualTo(RouteState.LEGACY);
          assertThat(context.getBean(RouteFlags.class).state(RouteFlags.DAILY_EXTRACT))
              .isEqualTo(RouteState.LEGACY);
          assertThat(context).hasSingleBean(InfoContributor.class);
        });
  }

  @Test
  void bindsRoutesAndSupportsModernAlias() {
    contextRunner
        .withPropertyValues("pas.routing.premium-batch=modern", "pas.routing.daily-extract=shadow")
        .run(
            context -> {
              RouteFlags routes = context.getBean(RouteFlags.class);
              assertThat(routes.state(RouteFlags.PREMIUM_BATCH)).isEqualTo(RouteState.NEW);
              assertThat(routes.state(RouteFlags.DAILY_EXTRACT)).isEqualTo(RouteState.SHADOW);
            });
  }

  @Test
  void rejectsUnknownRouteCapabilities() {
    contextRunner
        .withPropertyValues("pas.routing.unknown-capability=new")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalArgumentException.class);
            });
  }
}
