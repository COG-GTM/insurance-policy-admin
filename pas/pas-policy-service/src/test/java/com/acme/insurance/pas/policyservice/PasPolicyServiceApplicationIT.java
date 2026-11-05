package com.acme.insurance.pas.policyservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policyservice.observability.CorrelationIdFilter;
import com.acme.insurance.pas.policyservice.security.PasScopes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
// Tests disable metrics export by default; the Prometheus endpoint is part of what is verified.
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PasPolicyServiceApplicationIT {

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int apiPort;
  @LocalManagementPort int managementPort;
  @Autowired JdbcTemplate jdbc;

  @Test
  void livenessAndReadinessProbesAreUp() throws Exception {
    assertThat(get(managementPort, "/actuator/health/liveness").body()).contains("\"UP\"");
    HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");
    assertThat(readiness.statusCode()).isEqualTo(200);
    assertThat(readiness.body()).contains("\"UP\"");
  }

  @Test
  void healthHidesComponentDetails() throws Exception {
    HttpResponse<String> health = get(managementPort, "/actuator/health");
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).doesNotContain("database").doesNotContain("jdbc");
  }

  @Test
  void prometheusMetricsAreTaggedWithTheApplication() throws Exception {
    HttpResponse<String> metrics =
        get(managementPort, "/actuator/prometheus", TestJwt.wholeBook(PasScopes.OPS_READ));
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body())
        .contains("jvm_memory_used_bytes")
        .contains("process_uptime_seconds")
        .contains("application=\"pas-policy-service\"");
  }

  @Test
  void infoAndMetricsNeedTheOpsScope() throws Exception {
    for (String path : new String[] {"/actuator/info", "/actuator/prometheus"}) {
      assertThat(get(managementPort, path).statusCode()).as(path).isEqualTo(401);
      assertThat(get(managementPort, path, TestJwt.wholeBook(PasScopes.POLICY_READ)).statusCode())
          .as(path)
          .isEqualTo(403);
      assertThat(get(managementPort, path, TestJwt.wholeBook(PasScopes.OPS_READ)).statusCode())
          .as(path)
          .isEqualTo(200);
    }
  }

  @Test
  void onlyProbeInfoAndMetricsEndpointsAreExposed() throws Exception {
    String ops = TestJwt.wholeBook(PasScopes.OPS_READ + " " + PasScopes.POLICY_READ);
    for (String path :
        new String[] {
          "/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers"
        }) {
      assertThat(get(managementPort, path).statusCode()).as(path).isIn(401, 403, 404);
      assertThat(get(managementPort, path, ops).statusCode()).as(path).isIn(403, 404);
    }
  }

  @Test
  void applicationPortDeniesEverythingByDefault() throws Exception {
    for (String path :
        new String[] {"/", "/api/v1/policies/POL-00000001", "/actuator/health", "/error"}) {
      assertThat(get(apiPort, path).statusCode()).as(path).isIn(401, 403);
    }
  }

  @Test
  void responsesCarryACorrelationId() throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + "/api/v1/ping"))
                .header(CorrelationIdFilter.HEADER, "test-correlation-0001")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.headers().firstValue(CorrelationIdFilter.HEADER))
        .contains("test-correlation-0001");
  }

  @Test
  void flywayBaselineCreatedTheTargetSchema() {
    Integer schemas =
        jdbc.queryForObject(
            "select count(*) from information_schema.schemata where schema_name = 'acmeins'",
            Integer.class);
    assertThat(schemas).isEqualTo(1);
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '1' and success",
            Integer.class);
    assertThat(applied).isEqualTo(1);
  }

  @Test
  void logsAreStructuredJson(CapturedOutput output) {
    org.slf4j.LoggerFactory.getLogger(getClass()).info("structured logging check");
    String line =
        output
            .getOut()
            .lines()
            .filter(l -> l.contains("structured logging check"))
            .findFirst()
            .orElseThrow();
    assertThat(line).startsWith("{").endsWith("}").contains("\"@timestamp\"");
    assertThat(line).contains("pas-policy-service");
  }

  private HttpResponse<String> get(int port, String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(int port, String path, String jwt) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + jwt)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
