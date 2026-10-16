package com.acme.insurance.pas.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // @SpringBootTest disables metrics export by default; the prometheus endpoint needs it
    properties = "management.prometheus.metrics.export.enabled=true")
@Testcontainers
class PasBatchApplicationIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate rest;
  @Autowired private JobLauncher jobLauncher;
  @Autowired private Job noopJob;

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
  void noopJobCompletes() throws Exception {
    JobParameters params =
        new JobParametersBuilder().addLong("run.id", System.currentTimeMillis()).toJobParameters();
    JobExecution execution = jobLauncher.run(noopJob, params);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
  }
}
