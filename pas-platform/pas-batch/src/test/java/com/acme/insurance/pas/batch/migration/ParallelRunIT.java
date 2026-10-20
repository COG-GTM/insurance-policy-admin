package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.extract.DailyExtractJobConfig;
import com.acme.insurance.pas.batch.migration.parallelrun.ParallelRunService.OutputSummary;
import com.acme.insurance.pas.batch.migration.report.CheckReport;
import com.acme.insurance.pas.batch.migration.triage.Classification;
import com.acme.insurance.pas.batch.schedule.ExtractJobLauncher;
import com.acme.insurance.pas.batch.support.ParityFixtures;
import com.acme.insurance.pas.batch.support.SourceStandIn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
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

/**
 * AB-529 daily parallel-run diff: the AB-527 dailyExtractJob output versus the legacy capture (here
 * the committed oracle goldens standing in for mainframe files), and PREMBAT PREMIUMS rows versus
 * {@code pas_shadow.premiums}. Every difference is classified; a missing output makes the day
 * INCOMPLETE.
 */
@SpringBootTest(
    properties = {
      "pas.transfer.enabled=false",
      "pas.migration.enabled=true",
      "pas.migration.source.username=sa"
    })
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ParallelRunIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  static final String SOURCE = SourceStandIn.url("acmeins_parallel_it");
  static final LocalDate DAY = LocalDate.of(2026, 10, 12);
  static final Path WORK = tempDir();

  static Path tempDir() {
    try {
      Path dir = Files.createTempDirectory("ab529-parallel-run");
      Files.writeString(
          dir.resolve("triage-register.json"),
          """
          {"entries": [
            {"id": "PR-001", "check": "PARALLEL_RUN", "scope": "PREMIUMS", "key": "*",
             "kind": "EXTRA_IN_NEW", "classification": "APPROVED_FIX", "quirk": "Q-22",
             "decision": "docs/modernization/quirk-register.md#q-22", "rationale": "rerun replace"},
            {"id": "PR-T1", "check": "PARALLEL_RUN", "scope": "dailyExtractJob/policy_extract.dat",
             "key": "*", "kind": "CHANGED", "field": "TOTAL_PREMIUM", "classification": "BUG",
             "ticket": "AB-529-IT", "rationale": "injected"}
          ]}
          """);
      return dir;
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("pas.migration.source.url", () -> SOURCE);
    registry.add("pas.extract.output-dir", () -> WORK.resolve("new").toString());
    registry.add("pas.migration.legacy-output-dir", () -> WORK.resolve("legacy").toString());
    registry.add("pas.migration.report-dir", () -> WORK.resolve("reports").toString());
    registry.add("pas.migration.triage-register", () -> WORK.resolve("triage-register.json"));
    registry.add(
        "pas.migration.quirk-register",
        () -> ParityFixtures.repoRoot().resolve("docs/modernization/quirk-register.md"));
    registry.add("pas.pii.encryption-key", SourceStandIn::randomKey);
    registry.add("pas.pii.blind-index-key", SourceStandIn::randomKey);
  }

  @BeforeAll
  static void setUp() throws Exception {
    ParityFixtures.migratePasSchema(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    SourceStandIn.seed(SOURCE);
    Path legacy = WORK.resolve("legacy/dailyExtractJob/" + DAY);
    Files.createDirectories(legacy);
    for (String file :
        List.of(DailyExtractJobConfig.POLICY_FILE, DailyExtractJobConfig.COVERAGE_FILE)) {
      Files.copy(
          ParityFixtures.extractsDir().resolve("golden/daily-2026-10-12").resolve(file),
          legacy.resolve(file));
    }
  }

  @Autowired JobLauncher launcher;

  @Autowired
  @Qualifier("dailyExtractJob")
  Job dailyExtract;

  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate target;
  @Autowired MigrationChecks checks;

  @Test
  @Order(1)
  void extractsMatchAndMissingShadowPremiumsMakeTheDayIncomplete() throws Exception {
    ParityFixtures.load(dataSource, ParityFixtures.run("daily-2026-10-12"));
    assertThat(
            launcher
                .run(dailyExtract, ExtractJobLauncher.parameters(DAY, LocalDateTime.now()))
                .getStatus())
        .isEqualTo(BatchStatus.COMPLETED);

    CheckReport report = checks.parallelRun(DAY, "it-parallel-1");

    assertThat(report.differences()).isEmpty();
    assertThat(report.unexplained()).isZero();
    assertThat(report.status()).isEqualTo("INCOMPLETE");
    @SuppressWarnings("unchecked")
    List<OutputSummary> outputs = (List<OutputSummary>) report.sections().get("outputs");
    assertThat(outputs)
        .extracting(o -> o.output() + "=" + o.status())
        .containsExactly(
            "dailyExtractJob/policy_extract.dat=MATCH",
            "dailyExtractJob/coverage_extract.dat=MATCH",
            "PREMIUMS=NEW_NOT_AVAILABLE");
  }

  @Test
  @Order(2)
  void injectedDifferencesAreDetectedAndClassified() throws Exception {
    // Shadow premiums as AB-523 would write them: POL-00000001 matches, POL-00000002 differs,
    // POL-00000003 is recalculated today (Q-22 replace; DB2 kept the 2026-10-11 row).
    target.update(
        "INSERT INTO pas_shadow.premiums (shadow_run_id, business_date, policy_number,"
            + " coverage_seq, term_effective_date, term_expiry_date, base_rate, tax_amt,"
            + " total_premium, installment_code, installment_amt, calc_date, calc_by) VALUES"
            + " ('run-001', DATE '2026-10-12', 'POL-00000001', 1, DATE '2025-01-01',"
            + " DATE '2026-01-01', 1250.0000, 25.00, 1275.00, 'AN', 1275.00, DATE '2026-10-12',"
            + " 'PREMBAT'),"
            + " ('run-001', DATE '2026-10-12', 'POL-00000002', 1, DATE '2025-03-15',"
            + " DATE '2026-03-15', 890.5000, 17.81, 908.32, 'MO', 75.69, DATE '2026-10-12',"
            + " 'PREMBAT'),"
            + " ('run-001', DATE '2026-10-12', 'POL-00000003', 1, DATE '2025-06-01',"
            + " DATE '2026-06-01', 4500.0000, 90.00, 4590.00, 'QT', 1147.50, DATE '2026-10-12',"
            + " 'PREMBAT')");
    Path legacy = WORK.resolve("legacy/dailyExtractJob/" + DAY + "/policy_extract.dat");
    byte[] bytes = Files.readAllBytes(legacy);
    int offset =
        com.acme.insurance.pas.batch.extract.ExtractRecordLayouts.POLICY_EXTRACT.offsetOf(
            "TOTAL_PREMIUM");
    bytes[offset] = (byte) (bytes[offset] == '0' ? '1' : '0');
    Files.write(legacy, bytes);

    CheckReport report = checks.parallelRun(DAY, "it-parallel-2");

    assertThat(report.status()).isEqualTo("FAIL");
    assertThat(report.differences())
        .extracting(d -> d.scope() + ":" + d.kind() + ":" + d.classification())
        .containsExactlyInAnyOrder(
            "dailyExtractJob/policy_extract.dat:CHANGED:BUG",
            "PREMIUMS:CHANGED:UNCLASSIFIED",
            "PREMIUMS:EXTRA_IN_NEW:APPROVED_FIX");
    assertThat(report.count(Classification.APPROVED_FIX)).isEqualTo(1);
    assertThat(report.unexplained()).isEqualTo(2);
    assertThat(report.differences())
        .filteredOn(d -> d.kind().equals("CHANGED") && d.scope().equals("PREMIUMS"))
        .singleElement()
        .satisfies(d -> assertThat(d.fields()).containsExactly("TOTAL_PREMIUM"));
    assertThat(report.differences())
        .filteredOn(d -> d.classification() == Classification.APPROVED_FIX)
        .singleElement()
        .satisfies(
            d -> assertThat(d.reference()).isEqualTo("docs/modernization/quirk-register.md#q-22"));
    assertThat(Files.readString(WORK.resolve("reports/parallel-run/latest.md")))
        .contains("**Status: FAIL**", "APPROVED_FIX (PR-001)");
  }
}
