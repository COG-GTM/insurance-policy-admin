package com.acme.insurance.pas.policy.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.routing.RouteConfiguration;
import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

class IbmMqConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(IbmMqConfiguration.class);
  private final ApplicationContextRunner tlsRequirementRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(RouteConfiguration.class, IbmMqTlsRequirement.class);

  @Test
  void requiresTlsCipherSuiteWhenMqHostIsConfigured() {
    contextRunner
        .withPropertyValues(
            "pas.bridge.mq.host=mq.example",
            "pas.bridge.mq.queue-manager=QM1",
            "pas.bridge.mq.channel=APP.SVRCONN")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "pas.bridge.mq.tls-cipher-suite must be configured when pas.bridge.mq.host is set"));
  }

  @Test
  void leavesMqConfigurationDisabledWhenHostIsMissing() {
    contextRunner.run(
        context -> assertThat(context).hasNotFailed().doesNotHaveBean(MQConnectionFactory.class));
  }

  @Test
  void requiresTlsForNewDocumentBridgeOutsideLocalAndTestProfiles() {
    tlsRequirementRunner
        .withInitializer(context -> activateProfile(context, "prod"))
        .withPropertyValues("pas.routing.document-events=modern")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "pas.bridge.mq.tls-cipher-suite must be configured when document-events is NEW outside local/test profiles"));
  }

  @Test
  void allowsNewDocumentBridgeWithoutTlsCipherInLocalAndTestProfiles() {
    for (String profile : List.of("local", "test")) {
      tlsRequirementRunner
          .withInitializer(context -> activateProfile(context, profile))
          .withPropertyValues("pas.routing.document-events=new")
          .run(context -> assertThat(context).hasNotFailed());
    }
  }

  private static void activateProfile(ConfigurableApplicationContext context, String profile) {
    context.getEnvironment().setActiveProfiles(profile);
  }
}
