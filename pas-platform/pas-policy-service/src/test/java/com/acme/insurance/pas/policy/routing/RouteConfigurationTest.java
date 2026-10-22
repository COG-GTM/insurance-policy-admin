package com.acme.insurance.pas.policy.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RouteConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(RouteConfiguration.class);

  @Test
  void rejectsNewBrokerPortalReadWhenPolicyInquiryDoesNotServeTraffic() {
    contextRunner
        .withPropertyValues(
            "pas.routing.broker-portal-read=new", "pas.routing.policy-inquiry=legacy")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "Route flag broker-portal-read requires policy-inquiry to serve traffic"));
  }

  @Test
  void rejectsShadowBrokerPortalReadWhenPolicyInquiryDoesNotServeTraffic() {
    contextRunner
        .withPropertyValues(
            "pas.routing.broker-portal-read=shadow", "pas.routing.policy-inquiry=legacy")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "Route flag broker-portal-read requires policy-inquiry to serve traffic"));
  }

  @Test
  void allowsBrokerPortalReadWhenPolicyInquiryIsShadowServing() {
    contextRunner
        .withPropertyValues(
            "pas.routing.broker-portal-read=new", "pas.routing.policy-inquiry=shadow")
        .run(context -> assertThat(context).hasNotFailed().hasBean("routeFlags"));
  }
}
