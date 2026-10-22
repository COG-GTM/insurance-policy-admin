package com.acme.insurance.pas.batch.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import com.acme.insurance.pas.rating.PremiumCalculator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.explore.JobExplorer;
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
class BatchPlatformIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private JobExplorer jobExplorer;

  @Autowired private PremiumCalculator premiumCalculator;

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + BatchJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void healthAndReadinessArePublicAndDatabaseAware() throws Exception {
    ResponseEntity<String> live = get("/actuator/health/liveness", null);
    ResponseEntity<String> readiness = get("/actuator/health/readiness", null);
    ResponseEntity<String> detailedReadiness =
        get("/actuator/health/readiness", BatchJwtTestSupport.mint(PasScopes.BATCH_OPERATE));

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
            get("/actuator/env", BatchJwtTestSupport.mint(PasScopes.BATCH_OPERATE)).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<String> metrics =
        get("/actuator/prometheus", BatchJwtTestSupport.mint(PasScopes.BATCH_OPERATE));
    assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(metrics.getBody()).contains("jvm_memory_used_bytes");
    assertThat(metrics.getBody()).contains("application=\"pas-batch\"");
  }

  @Test
  void exposesAuthenticatedWhoamiAndDefaultSecurityHeaders() throws Exception {
    ResponseEntity<String> response =
        get("/api/batch/platform/whoami", BatchJwtTestSupport.mint(PasScopes.BATCH_OPERATE));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"subject\":\"batch-test-agent\"");
    assertThat(response.getBody()).contains("\"scopes\":[\"" + PasScopes.BATCH_OPERATE + "\"]");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
  }

  @Test
  void migratesBatchMetadataOnlyAndUsesTheConfiguredPrefix() {
    List<String> versions =
        jdbcTemplate.queryForList(
            "SELECT version FROM pas_batch.flyway_schema_history WHERE version IS NOT NULL",
            String.class);
    Integer metadataTables =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'pas_batch' "
                + "AND table_name IN ('batch_job_instance', 'batch_job_execution', "
                + "'batch_job_execution_params', 'batch_step_execution', "
                + "'batch_step_execution_context', 'batch_job_execution_context')",
            Integer.class);
    Integer pasSchema =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'pas'",
            Integer.class);

    assertThat(versions).containsExactly("1");
    assertThat(metadataTables).isEqualTo(6);
    assertThat(pasSchema).isZero();
    assertThat(jobExplorer.getJobNames()).isEmpty();
    assertThat(premiumCalculator).isNotNull();
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
