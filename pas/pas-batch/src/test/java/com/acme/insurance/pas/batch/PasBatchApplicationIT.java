package com.acme.insurance.pas.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// With more than one job Spring Boot needs PAS_BATCH_JOB to launch one; this test only starts the
// application and checks the catalog.
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@Import(PasBatchApplicationIT.Postgres.class)
@ExtendWith(OutputCaptureExtension.class)
class PasBatchApplicationIT {

  @TestConfiguration(proxyBeanMethods = false)
  static class Postgres {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
      return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
  }

  @Autowired JobCatalogReporter catalog;

  @Test
  void startsAndLogsTheJobCatalogAsJson(CapturedOutput output) {
    assertThat(catalog.jobNames()).contains("actuarialMonthlyExposure", "claimsDailyExtract");
    String line =
        output
            .getOut()
            .lines()
            .filter(l -> l.contains("pas-batch registered jobs"))
            .findFirst()
            .orElseThrow();
    assertThat(line).startsWith("{").contains("\"@timestamp\"").contains("pas-batch");
  }
}
