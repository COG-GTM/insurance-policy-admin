package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    properties = {"spring.main.web-application-type=none", "pas.routing.premium-batch=legacy"})
class PremiumLegacyRouteIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JobLauncher jobLauncher;

  @Autowired
  @Qualifier("premiumRecalculationJob")
  private Job premiumJob;

  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void prepareDatabase(DynamicPropertyRegistry registry) {
    PasTestDatabase.migratePas(POSTGRES);
  }

  @Test
  void disablesWithoutAnyWritesOrSummary() throws Exception {
    JobExecution execution =
        jobLauncher.run(
            premiumJob,
            new JobParametersBuilder()
                .addString("businessDate", LocalDate.of(2024, 10, 15).toString(), true)
                .addLong("rerun", 501L, true)
                .toJobParameters());

    assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(execution.getExitStatus().getExitCode()).isEqualTo("DISABLED");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_batch.premium_run_summary WHERE job_execution_id = ?",
                Integer.class,
                execution.getId()))
        .isZero();
  }
}
