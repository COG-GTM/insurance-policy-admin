package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.acme.insurance.pas.batch.exposure.MonthlyExposureJobConfig;
import com.acme.insurance.pas.batch.schedule.ExtractJobLauncher;
import com.acme.insurance.pas.batch.support.ParityFixtures;
import com.acme.insurance.pas.batch.support.ParityFixtures.Run;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AB-527 byte-for-byte parity: runs dailyExtractJob / monthlyExposureJob against each snapshot in
 * {@code parity/extracts/fixtures/} (5 consecutive daily + 5 monthly business dates) and compares
 * every output file with the independent oracle's golden ({@code parity/extracts/oracle.py}).
 */
@SpringBootTest(properties = "pas.transfer.enabled=false")
@Testcontainers
class ExtractParityIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @TempDir static Path outputDir;

  private static final Map<String, List<String>> FILES =
      Map.of(
          DailyExtractJobConfig.JOB_NAME,
          List.of(DailyExtractJobConfig.POLICY_FILE, DailyExtractJobConfig.COVERAGE_FILE),
          MonthlyExposureJobConfig.JOB_NAME,
          List.of(MonthlyExposureJobConfig.EXPOSURE_FILE));

  @Autowired private JobLauncher jobLauncher;
  @Autowired private Map<String, Job> jobs;
  @Autowired private DataSource dataSource;
  @Autowired private ExtractOutputs outputs;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("pas.extract.output-dir", () -> outputDir.toString());
  }

  @BeforeAll
  static void schema() {
    ParityFixtures.migratePasSchema(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
  }

  static Stream<Run> runs() throws Exception {
    return ParityFixtures.runs().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("runs")
  void outputMatchesGoldenByteForByte(Run run) throws Exception {
    ParityFixtures.load(dataSource, run);
    JobExecution execution =
        jobLauncher.run(
            jobs.get(run.job()),
            ExtractJobLauncher.parameters(run.businessDate(), LocalDateTime.now()));
    assertThat(execution.getStatus())
        .as(execution.getAllFailureExceptions().toString())
        .isEqualTo(BatchStatus.COMPLETED);
    for (String file : FILES.get(run.job())) {
      Path actual = outputs.location(run.job(), run.businessDate(), file);
      assertSameBytes(run, file, Files.readAllBytes(actual));
    }
  }

  private static void assertSameBytes(Run run, String file, byte[] actual) throws Exception {
    byte[] expected = Files.readAllBytes(ParityFixtures.golden(run, file));
    if (java.util.Arrays.equals(expected, actual)) {
      return;
    }
    String[] want = new String(expected, StandardCharsets.ISO_8859_1).split("\r\n", -1);
    String[] got = new String(actual, StandardCharsets.ISO_8859_1).split("\r\n", -1);
    for (int i = 0; i < Math.max(want.length, got.length); i++) {
      String w = i < want.length ? want[i] : "<missing>";
      String g = i < got.length ? got[i] : "<missing>";
      if (!w.equals(g)) {
        fail(
            "%s/%s differs at record %d%n golden: [%s]%n actual: [%s]",
            run.id(), file, i + 1, w, g);
      }
    }
    fail("%s/%s differs (%d vs %d bytes)", run.id(), file, expected.length, actual.length);
  }
}
