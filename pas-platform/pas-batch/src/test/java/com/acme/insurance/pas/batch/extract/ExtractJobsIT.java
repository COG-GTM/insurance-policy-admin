package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "pas.routing.daily-extract=legacy",
      "pas.routing.monthly-exposure=legacy",
      "pas.batch.extract.work-dir=${java.io.tmpdir}/pas-batch-extract-it"
    })
class ExtractJobsIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JobLauncher jobLauncher;
  @Autowired private Job dailyExtractJob;
  @Autowired private Job monthlyExposureJob;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  @DynamicPropertySource
  static void prepareDatabase(DynamicPropertyRegistry registry) {
    PasTestDatabase.migratePas(POSTGRES);
  }

  @BeforeEach
  void loadFixture() {
    new ResourceDatabasePopulator(new ClassPathResource("extract/extract-fixture.sql"))
        .execute(jdbcTemplate.getDataSource());
  }

  @Test
  void dailyJobWritesGoldenFilesAndLegacyRouteSkipsDelivery() throws Exception {
    for (long rerun = 1; rerun <= 5; rerun++) {
      JobExecution execution = jobLauncher.run(dailyExtractJob, parameters(rerun));
      Path output = outputDirectory(execution, "dailyExtractJob");

      assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
      assertGolden(output.resolve("policy_extract.dat"), "extract/golden/daily/policy_extract.dat");
      assertGolden(
          output.resolve("coverage_extract.dat"), "extract/golden/daily/coverage_extract.dat");
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_batch.extract_delivery", Integer.class))
        .isZero();
  }

  @Test
  void monthlyJobWritesGoldenCsvWithCoverageRowCount() throws Exception {
    JobExecution execution = jobLauncher.run(monthlyExposureJob, parameters(1));
    Path output = outputDirectory(execution, "monthlyExposureJob");
    Path file = output.resolve("exposure_monthly.D250210.csv");

    assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
    assertGolden(file, "extract/golden/monthly/exposure_monthly.csv");
    assertThat(Files.readString(file)).isEqualTo("HOM,,FIRE,,,2,250000.00,14.00,1000.00\r\n");
  }

  @Test
  void failedJobIncrementsMetricAndLogsOnlyStructuredFailureMetadata(CapturedOutput capturedOutput)
      throws Exception {
    JobParameters parameters =
        new JobParametersBuilder()
            .addString("businessDate", "not-a-date", true)
            .addLong("rerun", 99L, true)
            .toJobParameters();

    JobExecution execution = jobLauncher.run(dailyExtractJob, parameters);

    assertThat(execution.getStatus().name()).isEqualTo("FAILED");
    assertThat(
            meterRegistry
                .get("pas.batch.job.failures")
                .tag("job", "dailyExtractJob")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(capturedOutput.getOut() + capturedOutput.getErr())
        .contains("pas.batch.job.failed")
        .doesNotContain("AB0000000001");
  }

  private static JobParameters parameters(long rerun) {
    JobParametersBuilder builder =
        new JobParametersBuilder().addString("businessDate", "2025-02-10", true);
    builder.addLong("rerun", rerun, true);
    return builder.toJobParameters();
  }

  private static Path outputDirectory(JobExecution execution, String jobName) {
    return Path.of(
        System.getProperty("java.io.tmpdir"),
        "pas-batch-extract-it",
        jobName,
        "2025-02-10",
        execution.getId().toString());
  }

  private static void assertGolden(Path actual, String goldenResource) throws Exception {
    byte[] expected = new ClassPathResource(goldenResource).getInputStream().readAllBytes();
    assertThat(Files.readAllBytes(actual)).isEqualTo(expected);
  }
}
