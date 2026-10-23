package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("local")
class LocalProfileStartupIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private ApplicationContext applicationContext;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add("PAS_DEV_JWT_PUBLIC_KEY", () -> PolicyJwtTestSupport.PUBLIC_KEY_FILE.toString());
  }

  @Test
  void localProfileStartsWithAConfiguredPublicKey() {
    assertThat(applicationContext.getBean(JwtDecoder.class)).isNotNull();
  }
}
