package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Tag("load")
@Testcontainers
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "pas.routing.premium-batch=new",
      "pas.batch.premium.report-dir=${java.io.tmpdir}/pas-batch-premium-load"
    })
class PremiumJobLoadIT {
  private static final Logger log = LoggerFactory.getLogger(PremiumJobLoadIT.class);
  private static final int POLICY_COUNT = Integer.getInteger("pas.load.policies", 250_000);

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
  void calculatesDefaultBookAtLoad() throws Exception {
    jdbcTemplate.update("DELETE FROM pas.premiums");
    jdbcTemplate.update("DELETE FROM pas.policies");
    jdbcTemplate.update("DELETE FROM pas.coverages");
    jdbcTemplate.update(
        "INSERT INTO pas.policies "
            + "(policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id) "
            + "SELECT 'LP' || LPAD(n::text, 10, '0'), 'AUT', "
            + "CASE WHEN n % 10 = 0 THEN 'PN' ELSE 'AC' END, DATE '2024-01-01', "
            + "DATE '2025-01-01', 'H' || LPAD(n::text, 9, '0') "
            + "FROM generate_series(1, ?) AS g(n)",
        POLICY_COUNT);
    jdbcTemplate.update(
        "INSERT INTO pas.coverages "
            + "(policy_number, sequence_num, coverage_type, premium, effective_date, expiry_date) "
            + "SELECT p.policy_number, s, 'BASE', 0, DATE '2024-01-01', DATE '2025-01-01' "
            + "FROM pas.policies p CROSS JOIN generate_series(1, 2) s");

    long startedAt = System.nanoTime();
    JobExecution execution =
        jobLauncher.run(
            premiumJob,
            new JobParametersBuilder()
                .addString("businessDate", "2024-10-15", true)
                .addLong("rerun", 9001L, true)
                .toJobParameters());
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    int activePolicies = POLICY_COUNT - POLICY_COUNT / 10;
    assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pas.premiums", Long.class))
        .isEqualTo((long) activePolicies);
    log.info(
        "premium-load policies={} active={} gridSize={} chunkSize={} concurrency={} elapsedMs={}",
        POLICY_COUNT,
        activePolicies,
        8,
        500,
        8,
        elapsedMs);
  }
}
