package com.acme.insurance.pas.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.observability.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "PAS_DB_PASSWORD=test-password"})
@Testcontainers
@ExtendWith({SpringExtension.class, OutputCaptureExtension.class})
class PolicyServiceApplicationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    int applicationPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void exposesHealthMetricsAndCorrelationId(CapturedOutput output) {
        assertHealth("/actuator/health/liveness");
        assertHealth("/actuator/health/readiness");

        var metrics = restTemplate.getForEntity(managementUrl("/actuator/prometheus"), String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody()).contains("jvm_memory_used_bytes").contains("application=\"pas-policy-service\"");

        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER_NAME, "it-123");
        var response = restTemplate.exchange(
                "http://localhost:" + applicationPort + "/missing",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo("it-123");
        assertThat(output.getOut())
                .contains("\"@timestamp\"")
                .contains("\"log\":{\"level\":\"INFO\"")
                .contains("\"correlationId\":\"it-123\"");
    }

    private void assertHealth(String path) {
        var response = restTemplate.getForEntity(managementUrl(path), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    private String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
