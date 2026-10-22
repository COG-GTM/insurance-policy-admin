package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("it")
@ExtendWith(OutputCaptureExtension.class)
class PiiLoggingIT {
  private static final Logger LOGGER = LoggerFactory.getLogger(PiiLoggingIT.class);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void structuredEcsLogsMaskSelectedSensitiveValues(CapturedOutput output) {
    LOGGER.info("taxId=987654320");
    LOGGER.info("ssn 123-45-6789");
    LOGGER.info("date_of_birth=19800417");

    assertThat(output.getOut())
        .contains("\"@timestamp\"")
        .contains("\"message\":\"taxId=****\"")
        .contains("\"message\":\"ssn ****\"")
        .contains("\"message\":\"date_of_birth=****\"")
        .doesNotContain("987654320", "123-45-6789", "19800417");
  }
}
