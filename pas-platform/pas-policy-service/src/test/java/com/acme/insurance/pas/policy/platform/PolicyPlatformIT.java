package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.security.PasScopes;
import com.acme.insurance.pas.rating.FactorMode;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.RatingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class PolicyPlatformIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private PremiumCalculator premiumCalculator;

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void healthAndReadinessArePublicAndDatabaseAware() throws Exception {
    ResponseEntity<String> live = get("/actuator/health/liveness", null);
    ResponseEntity<String> readiness = get("/actuator/health/readiness", null);
    ResponseEntity<String> detailedReadiness =
        get("/actuator/health/readiness", PolicyJwtTestSupport.mint(PasScopes.POLICY_READ));

    assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(live.getBody()).contains("\"status\":\"UP\"");
    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readiness.getBody()).contains("\"status\":\"UP\"");
    assertThat(detailedReadiness.getBody()).contains("\"db\"");
  }

  @Test
  void securesActuatorAndExposesOnlyTheAllowlistedEndpoints() throws Exception {
    assertThat(get("/actuator/prometheus", null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/actuator/info", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            get("/actuator/env", PolicyJwtTestSupport.mint(PasScopes.POLICY_READ)).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<String> metrics =
        get("/actuator/prometheus", PolicyJwtTestSupport.mint(PasScopes.POLICY_READ));
    assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(metrics.getBody()).contains("jvm_memory_used_bytes");
    assertThat(metrics.getBody()).contains("application=\"pas-policy-service\"");
  }

  @Test
  void actuatorInfoShowsEveryRouteAndOtherActuatorEndpointsStayHidden() throws Exception {
    String jwt = PolicyJwtTestSupport.mint(PasScopes.POLICY_READ);
    ResponseEntity<String> links = get("/actuator", jwt);
    ResponseEntity<String> info = get("/actuator/info", jwt);

    assertThat(links.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode actuatorLinks = new ObjectMapper().readTree(links.getBody()).path("_links");
    List<String> linkNames = new ArrayList<>();
    actuatorLinks.fieldNames().forEachRemaining(linkNames::add);
    assertThat(linkNames)
        .containsExactlyInAnyOrder("self", "health", "health-path", "info", "prometheus");

    JsonNode routes = new ObjectMapper().readTree(info.getBody()).path("routes");
    List<String> routeNames = new ArrayList<>();
    routes.fieldNames().forEachRemaining(routeNames::add);
    assertThat(routeNames).containsExactlyInAnyOrderElementsOf(RouteFlags.CAPABILITIES);
    for (String endpoint :
        List.of(
            "env",
            "beans",
            "configprops",
            "mappings",
            "loggers",
            "heapdump",
            "threaddump",
            "metrics",
            "shutdown",
            "flyway")) {
      assertThat(get("/actuator/" + endpoint, jwt).getStatusCode())
          .as("Actuator endpoint %s", endpoint)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Test
  void exposesAuthenticatedWhoamiAndDefaultSecurityHeaders() throws Exception {
    ResponseEntity<String> response =
        get(
            "/api/v1/platform/whoami",
            PolicyJwtTestSupport.mint(PasScopes.POLICY_READ + " " + PasScopes.POLICY_WRITE));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"subject\":\"policy-test-agent\"");
    assertThat(response.getBody())
        .contains(
            "\"scopes\":[\"" + PasScopes.POLICY_READ + "\",\"" + PasScopes.POLICY_WRITE + "\"]");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
  }

  @Test
  void migratesOnlyThePolicySchemaAtExpectedVersions() {
    List<String> versions =
        jdbcTemplate.queryForList(
            "SELECT version FROM pas.flyway_schema_history WHERE version IS NOT NULL",
            String.class);

    assertThat(versions)
        .containsExactly(
            "1", "2", "3", "4", "5", "10", "20", "30", "31", "32", "33", "34", "40", "41", "60",
            "80");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pas'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void ratesLegacyAutomaticPoliciesFromTheMigratedParameters() {
    var breakdown =
        premiumCalculator.calculate(
            new RatingRequest("AUT", LocalDate.of(2024, 10, 15), null, null));
    assertThat(breakdown.factorMode()).isEqualTo(FactorMode.LEGACY);
    assertThat(breakdown.totalPremium()).isEqualByComparingTo("904.75");
  }

  private ResponseEntity<String> get(String path, String jwt) {
    HttpHeaders headers = new HttpHeaders();
    if (jwt != null) {
      headers.setBearerAuth(jwt);
    }
    return restTemplate.exchange(
        "http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
