package com.acme.insurance.pas.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
  void probesAndPrometheusAreExposed() {
    for (String path :
        new String[] {
          "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus"
        }) {
      ResponseEntity<String> res = rest.getForEntity(path, String.class);
      assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
    }
  }

  @Test
  void sensitiveActuatorEndpointsAreNotExposed() {
    ResponseEntity<String> res = rest.getForEntity("/actuator/env", String.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void pingEndpointResponds() {
    ResponseEntity<String> res = rest.getForEntity("/api/v2/ping", String.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).contains("pas-policy-service", "UP");
  }
}
