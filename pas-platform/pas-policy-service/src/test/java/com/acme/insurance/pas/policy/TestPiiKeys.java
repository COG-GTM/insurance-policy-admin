package com.acme.insurance.pas.policy;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Random per-JVM PII keys for tests; no key material is committed. */
public final class TestPiiKeys {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String ENCRYPTION_KEY = randomKey();
  private static final String BLIND_INDEX_KEY = randomKey();

  private TestPiiKeys() {}

  public static String randomKey() {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  public static void register(DynamicPropertyRegistry registry) {
    registry.add("PAS_PII_ENCRYPTION_KEY", () -> ENCRYPTION_KEY);
    registry.add("PAS_PII_BLIND_INDEX_KEY", () -> BLIND_INDEX_KEY);
  }
}
