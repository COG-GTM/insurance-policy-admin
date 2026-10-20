package com.acme.insurance.pas.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // @SpringBootTest disables metrics export by default; the prometheus endpoint needs it
    properties = "management.prometheus.metrics.export.enabled=true")
@Testcontainers
class PolicyServiceApplicationIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void piiKeys(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
    TestJwtIssuer.register(registry);
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void contextLoadsAndFlywayCreatedPasSchema() {
    List<String> schemas =
        jdbc.queryForList(
            "select schema_name from information_schema.schemata where schema_name = 'pas'",
            String.class);
    assertThat(schemas).containsExactly("pas");
  }

  @Test
  void healthProbesAreExposedWithoutAuthentication() {
    for (String path :
        new String[] {
          "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
        }) {
      ResponseEntity<String> res = rest.getForEntity(path, String.class);
      assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
    }
  }

  @Test
  void prometheusRequiresAuthentication() {
    assertThat(rest.getForEntity("/actuator/prometheus", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/actuator/prometheus", authenticated()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void otherActuatorEndpointsAreDisabledEvenWhenAuthenticated() {
    for (String path :
        new String[] {
          "/actuator/env",
          "/actuator/info",
          "/actuator/beans",
          "/actuator/configprops",
          "/actuator/heapdump",
          "/actuator/loggers",
          "/actuator/mappings",
          "/actuator/metrics",
          "/actuator/threaddump"
        }) {
      assertThat(rest.getForEntity(path, String.class).getStatusCode())
          .as(path + " anonymous")
          .isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(get(path, authenticated()).getStatusCode())
          .as(path + " authenticated")
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Test
  void pingRequiresAuthentication() {
    assertThat(rest.getForEntity("/api/v2/ping", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    ResponseEntity<String> res = get("/api/v2/ping", authenticated());
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).contains("pas-policy-service", "UP");
  }

  private static String authenticated() {
    return TestJwtIssuer.bearer().sign();
  }

  private ResponseEntity<String> get(String path, String bearer) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(bearer);
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
