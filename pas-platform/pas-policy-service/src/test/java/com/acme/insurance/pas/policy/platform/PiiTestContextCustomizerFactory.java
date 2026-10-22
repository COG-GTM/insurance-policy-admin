package com.acme.insurance.pas.policy.platform;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

public final class PiiTestContextCustomizerFactory implements ContextCustomizerFactory {
  private static final Map<String, String> TEST_KEYS = testKeys();

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    return new PiiTestContextCustomizer(TEST_KEYS);
  }

  private static Map<String, String> testKeys() {
    byte[] encryptionKey = new byte[32];
    byte[] blindIndexKey = new byte[32];
    SecureRandom random = new SecureRandom();
    random.nextBytes(encryptionKey);
    random.nextBytes(blindIndexKey);
    return Map.of(
        "pas.pii.encryption.active-key-id",
        "test-key",
        "pas.pii.encryption.active-key-version",
        "1",
        "pas.pii.encryption.active-key",
        Base64.getEncoder().encodeToString(encryptionKey),
        "pas.pii.blind-index.key",
        Base64.getEncoder().encodeToString(blindIndexKey));
  }

  private record PiiTestContextCustomizer(Map<String, String> properties)
      implements ContextCustomizer {
    @Override
    public void customizeContext(
        ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
      TestPropertyValues.of(properties).applyTo(context.getEnvironment());
    }
  }
}
