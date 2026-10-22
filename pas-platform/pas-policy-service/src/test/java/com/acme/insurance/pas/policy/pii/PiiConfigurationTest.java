package com.acme.insurance.pas.policy.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PiiConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(PiiConfiguration.class);

  @Test
  void failsFastWhenKeysAreMissing() {
    contextRunner.run(
        context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("pas.pii.encryption.active-key")
                .hasMessageNotContaining("secret"));
  }

  @Test
  void createsCipherAndBlindIndexFromIndependentKeys() {
    contextRunner
        .withPropertyValues(
            "pas.pii.encryption.active-key-id=unit-v1",
            "pas.pii.encryption.active-key-version=1",
            "pas.pii.encryption.active-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "pas.pii.blind-index.key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(com.acme.insurance.pas.pii.PiiCrypto.class));
  }

  @Test
  void rejectsReusedBlindIndexAndEncryptionKeys() {
    contextRunner
        .withPropertyValues(
            "pas.pii.encryption.active-key-id=unit-v1",
            "pas.pii.encryption.active-key-version=1",
            "pas.pii.encryption.active-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "pas.pii.blind-index.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("must be distinct"));
  }
}
