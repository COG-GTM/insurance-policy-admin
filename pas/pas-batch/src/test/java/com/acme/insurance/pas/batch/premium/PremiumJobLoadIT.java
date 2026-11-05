package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Load test of {@code premiumRecalculation} with the production defaults (8 partitions, chunks of
 * 500). Seeds {@code pas.premium.load.policies} active policies (default 50,000 in CI; {@code make
 * premium-load-test POLICIES=...} for a full-book run), times the job and checks the 30-minute
 * target, projecting linearly to {@code pas.premium.load.full-book} when fewer policies were
 * seeded. The result is written to {@code target/premium-load-test.txt}.
 */
@SpringBootTest(
    properties = {
      "spring.batch.job.enabled=false",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration/postgresql",
      "spring.datasource.hikari.maximum-pool-size=12"
    })
@Import(PremiumItSupport.Postgres.class)
class PremiumJobLoadIT {

  static final Duration TARGET = Duration.ofMinutes(30);

  @TempDir static Path reports;

  @DynamicPropertySource
  static void reportDir(DynamicPropertyRegistry registry) {
    registry.add("pas.premium.report-dir", () -> reports.toString());
  }

  @Autowired JobLauncher launcher;

  @Autowired
  @Qualifier(PremiumJobConfiguration.JOB)
  Job job;

  @Autowired JdbcTemplate jdbc;

  @Test
  void ratesTheBookWithinThirtyMinutes() throws Exception {
    long policies = Long.getLong("pas.premium.load.policies", 50_000L);
    long fullBook = Long.getLong("pas.premium.load.full-book", 1_000_000L);
    new PremiumItSupport(jdbc).reset();
    jdbc.update(
        """
        INSERT INTO acmeins.policies (policy_number, policy_type, policy_status, effective_date,
          expiry_date, policyholder_id, total_premium, deductible, coverage_limit)
        SELECT 'POL-' || lpad(g::text, 8, '0'),
               (ARRAY['AUT', 'HOM', 'COM', 'LIF', 'HLT'])[1 + g % 5],
               CASE WHEN g % 20 = 0 THEN 'CA' ELSE 'AC' END,
               DATE '2025-01-01', DATE '2026-01-01', 'H000000001', 1000.00, 500.00, 250000.00
          FROM generate_series(1, ?) AS g
        """,
        policies * 20 / 19 + 1);
    jdbc.execute("ANALYZE acmeins.policies");
    long active =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM acmeins.policies WHERE policy_status = 'AC'", Long.class);

    Instant start = Instant.now();
    JobExecution run =
        launcher.run(
            job,
            new JobParametersBuilder().addString("businessDate", "2025-03-01").toJobParameters());
    Duration elapsed = Duration.between(start, Instant.now());

    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(run.getExecutionContext().getLong(PremiumJobConfiguration.UPDATED))
        .isEqualTo(active);
    double perSecond = active / Math.max(elapsed.toMillis() / 1000.0, 0.001);
    Duration projected =
        active >= fullBook ? elapsed : Duration.ofMillis((long) (fullBook / perSecond * 1000));
    String result =
        String.format(
            "premiumRecalculation load test: %d active policies in %s (%.0f policies/s);"
                + " full book of %d projected at %s; target %s%n",
            active, elapsed, perSecond, fullBook, projected, TARGET);
    Files.writeString(Path.of("target/premium-load-test.txt"), result);
    System.out.print(result);
    assertThat(projected).isLessThan(TARGET);
  }
}
