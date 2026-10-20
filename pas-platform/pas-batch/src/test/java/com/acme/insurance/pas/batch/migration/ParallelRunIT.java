package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.extract.DailyExtractJobConfig;
import com.acme.insurance.pas.batch.migration.parallelrun.ParallelRunService.OutputSummary;
import com.acme.insurance.pas.batch.migration.report.CheckReport;
import com.acme.insurance.pas.batch.migration.triage.Classification;
import com.acme.insurance.pas.batch.premium.PremiumJobLauncher;
import com.acme.insurance.pas.batch.premium.PremiumRecalculationJobConfig;
import com.acme.insurance.pas.batch.premium.ShadowPremiumWriter;
import com.acme.insurance.pas.batch.schedule.ExtractJobLauncher;
import com.acme.insurance.pas.batch.support.ParityFixtures;
import com.acme.insurance.pas.batch.support.SourceStandIn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
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
 * the AB-523 premiumRecalculationJob run in shadow mode ({@code pas_shadow.premiums}). Every
 * difference is classified; a missing output makes the day INCOMPLETE.
 */
@SpringBootTest(
    properties = {
      "pas.transfer.enabled=false",
      "pas.migration.enabled=true",
      "pas.migration.source.username=sa",
      "pas.batch.premium.write-target=shadow",
      "pas.batch.premium.partitions=2"
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
    registry.add("pas.batch.premium.report-dir", () -> WORK.resolve("premium").toString());
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

  @Autowired
  @Qualifier(PremiumRecalculationJobConfig.JOB_NAME)
  Job premiumJob;

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
    // The real AB-523 job in shadow mode: rows go to pas_shadow.premiums, pas.premiums is untouched
    Integer pasPremiums = target.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class);
    JobExecution premiumRun = launcher.run(premiumJob, PremiumJobLauncher.parameters(DAY, false));
    assertThat(premiumRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(target.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class))
        .isEqualTo(pasPremiums);
    List<Map<String, Object>> shadow =
        target.queryForList(
            "SELECT * FROM pas_shadow.premiums WHERE shadow_run_id = ? AND business_date = ?"
                + " ORDER BY policy_number, coverage_seq",
            ShadowPremiumWriter.shadowRunId(premiumRun.getJobInstance().getInstanceId()),
            java.sql.Date.valueOf(DAY));
    assertThat(shadow).hasSizeGreaterThanOrEqualTo(3);
    assertThat(
            target.queryForObject(
                "SELECT write_target FROM pas_batch.premium_run_summary WHERE job_execution_id = ?",
                String.class,
                premiumRun.getId()))
        .isEqualTo("SHADOW");

    // Mainframe PREMBAT output for the same day: the same rows, except that the first policy's row
    // is not written (DB2 kept the earlier row of the term, Q-22) and the second has another total.
    JdbcTemplate db2 =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(SOURCE, "sa", ""));
    db2.execute("SET REFERENTIAL_INTEGRITY FALSE");
    db2.update("DELETE FROM ACMEINS.PREMIUMS WHERE CALC_DATE = ?", java.sql.Date.valueOf(DAY));
    List<String> columns = MigrationTable.PREMIUMS.columns();
    String insert =
        "INSERT INTO ACMEINS.PREMIUMS ("
            + String.join(", ", columns).toUpperCase(java.util.Locale.ROOT)
            + ") VALUES ("
            + String.join(", ", java.util.Collections.nCopies(columns.size(), "?"))
            + ")";
    for (int i = 1; i < shadow.size(); i++) {
      Map<String, Object> row = new java.util.HashMap<>(shadow.get(i));
      if (i == 1) {
        row.put("total_premium", ((BigDecimal) row.get("total_premium")).add(BigDecimal.ONE));
      }
      db2.update(
          "DELETE FROM ACMEINS.PREMIUMS WHERE POLICY_NUMBER = ? AND COVERAGE_SEQ = ?"
              + " AND TERM_EFFECTIVE_DATE = ?",
          row.get("policy_number"),
          row.get("coverage_seq"),
          row.get("term_effective_date"));
      db2.update(insert, columns.stream().map(row::get).toArray());
    }
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
