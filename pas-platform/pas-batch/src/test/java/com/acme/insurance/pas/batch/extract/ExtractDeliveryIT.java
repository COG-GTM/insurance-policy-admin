package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.alert.BatchAlert;
import com.acme.insurance.pas.batch.alert.BatchAlertNotifier;
import com.acme.insurance.pas.batch.exposure.MonthlyExposureJobConfig;
import com.acme.insurance.pas.batch.schedule.ExtractJobLauncher;
import com.acme.insurance.pas.batch.support.ParityFixtures;
import com.acme.insurance.pas.batch.support.ParityFixtures.Run;
import com.acme.insurance.pas.batch.support.SftpTestServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End to end with SFTP on: the daily job extracts and delivers to "claims" (bytes on the server
 * equal the oracle golden, plus .sha256 and .done); a broken "actuarial" destination fails the
 * monthly job and raises an alert to every notifier.
 */
@SpringBootTest
@Testcontainers
class ExtractDeliveryIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  static final SftpTestServer SFTP;
  static final Path WORK;
  static final List<BatchAlert> ALERTS = new CopyOnWriteArrayList<>();

  static {
    try {
      SFTP = new SftpTestServer().start();
      WORK = Files.createTempDirectory("extract-delivery-it");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @TestConfiguration
  static class CollectingNotifier {
    @Bean
    BatchAlertNotifier collectingNotifier() {
      return ALERTS::add;
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) throws Exception {
    String key = SFTP.writeClientKey(WORK).toString();
    String knownHosts = SFTP.writeKnownHosts(WORK).toString();
    registry.add("pas.extract.output-dir", () -> WORK.resolve("out").toString());
    registry.add("pas.transfer.enabled", () -> "true");
    registry.add("pas.transfer.retry.max-attempts", () -> "2");
    registry.add("pas.transfer.retry.initial-backoff", () -> "10ms");
    for (String dest : List.of("claims", "actuarial")) {
      String p = "pas.transfer.destinations." + dest + ".";
      registry.add(p + "host", SFTP::host);
      registry.add(p + "port", SFTP::port);
      registry.add(p + "username", () -> SftpTestServer.USER);
      registry.add(p + "private-key-file", () -> key);
      registry.add(p + "known-hosts-file", () -> knownHosts);
    }
    registry.add(
        "pas.transfer.destinations.claims.remote-directory", () -> SftpTestServer.REMOTE_DIR);
    registry.add("pas.transfer.destinations.actuarial.remote-directory", () -> "/no-such-dir");
  }

  @BeforeAll
  static void schema() {
    ParityFixtures.migratePasSchema(
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  @AfterAll
  static void stop() {
    SFTP.close();
  }

  @Autowired private JobLauncher jobLauncher;
  @Autowired private DataSource dataSource;

  @Autowired
  @Qualifier("dailyExtractJob")
  private Job dailyExtractJob;

  @Autowired
  @Qualifier("monthlyExposureJob")
  private Job monthlyExposureJob;

  @Test
  void dailyExtractIsDeliveredOverSftpWithChecksumAndDoneMarker() throws Exception {
    Run run = ParityFixtures.run("daily-2026-10-16");
    ParityFixtures.load(dataSource, run);

    JobExecution execution =
        jobLauncher.run(
            dailyExtractJob,
            ExtractJobLauncher.parameters(run.businessDate(), LocalDateTime.now()));

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    for (String file :
        List.of(DailyExtractJobConfig.POLICY_FILE, DailyExtractJobConfig.COVERAGE_FILE)) {
      assertThat(SFTP.read(file)).isEqualTo(Files.readAllBytes(ParityFixtures.golden(run, file)));
      assertThat(new String(SFTP.read(file + ".sha256"), StandardCharsets.US_ASCII))
          .endsWith("  " + file + "\n");
      assertThat(SFTP.list()).contains(file + ".done");
    }
  }

  @Test
  void failedDeliveryFailsTheJobAndAlerts() throws Exception {
    Run run = ParityFixtures.run("monthly-2026-10-01");
    ParityFixtures.load(dataSource, run);
    ALERTS.clear();

    JobExecution execution =
        jobLauncher.run(
            monthlyExposureJob,
            ExtractJobLauncher.parameters(run.businessDate(), LocalDateTime.now()));

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(ALERTS)
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.jobName()).isEqualTo(MonthlyExposureJobConfig.JOB_NAME);
              assertThat(a.failures()).anyMatch(f -> f.contains("2 attempt(s)"));
            });
  }
}
