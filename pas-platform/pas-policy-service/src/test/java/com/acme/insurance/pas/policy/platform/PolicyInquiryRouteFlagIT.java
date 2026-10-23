package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "pas.routing.policy-inquiry=legacy")
@ActiveProfiles("it")
class PolicyInquiryRouteFlagIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;
  @LocalServerPort private int port;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void allInquiryRoutesReturnEmpty503WhileLegacyIsServing() throws Exception {
    String jwt = PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("book", "ALL"));
    for (String path :
        java.util.List.of(
            "/api/v1/policies",
            "/api/v1/policies/POL-00000001",
            "/api/v1/policies/POL-00000001/coverages",
            "/api/v1/policies/POL-00000001/policyholder")) {
      HttpHeaders headers = new HttpHeaders();
      headers.setBearerAuth(jwt);
      ResponseEntity<String> response =
          restTemplate.exchange(
              "http://localhost:" + port + path,
              HttpMethod.GET,
              new HttpEntity<>(headers),
              String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(response.getBody()).isNullOrEmpty();
    }
  }
}
