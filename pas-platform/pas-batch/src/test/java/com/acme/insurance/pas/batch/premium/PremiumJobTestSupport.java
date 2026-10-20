package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL 16 (one container per JVM) with the {@code pas} schema migrated from
 * pas-policy-service's Flyway scripts (pas-batch owns no {@code pas} migrations) and the {@code
 * pas_batch} schema migrated by the application itself.
 */
@SpringBootTest(properties = "pas.batch.premium.partitions=4")
abstract class PremiumJobTestSupport {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Path REPORT_DIR;

  static {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("pas")
        .defaultSchema("pas")
        .createSchemas(true)
        .locations("filesystem:../pas-policy-service/src/main/resources/db/migration")
        .load()
        .migrate();
    try {
      REPORT_DIR = Files.createTempDirectory("premium-reports");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("pas.batch.premium.report-dir", REPORT_DIR::toString);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired JobLauncher jobLauncher;

  @Autowired
  @Qualifier(PremiumRecalculationJobConfig.JOB_NAME)
  Job premiumRecalculationJob;

  @Autowired PremiumRunSummaryRepository summaries;

  void resetBook() {
    jdbc.execute("TRUNCATE pas.premiums, pas.policies CASCADE");
  }

  void insertPolicy(
      String policyNumber,
      String policyType,
      String status,
      LocalDate effective,
      LocalDate expiry) {
    jdbc.update(
        "INSERT INTO pas.policies (policy_number, policy_type, policy_status, effective_date,"
            + " expiry_date, policyholder_id) VALUES (?, ?, ?, ?, ?, 'PH00000001')",
        policyNumber,
        policyType,
        status,
        effective,
        expiry);
  }

  JobExecution run(LocalDate runDate, boolean rerun) throws Exception {
    return jobLauncher.run(premiumRecalculationJob, PremiumJobLauncher.parameters(runDate, rerun));
  }

  /** Policy number for fixture row {@code index}: keeps fixture order = policy-number order. */
  static String fixturePolicyNumber(int index) {
    return String.format("PRB%09d", index + 1);
  }

  /** Fixture rows of {@code parity/programs/<program>/fixtures.csv} keyed by case id. */
  static Map<String, Map<String, String>> fixtures(String program) throws IOException {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (!Files.isDirectory(dir.resolve("parity/programs"))) {
      dir = dir.getParent();
    }
    List<String> lines =
        Files.readAllLines(dir.resolve("parity/programs/" + program + "/fixtures.csv"));
    String[] header = lines.get(0).split(",", -1);
    Map<String, Map<String, String>> rows = new LinkedHashMap<>();
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      String[] cells = line.split(",", -1);
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < header.length; i++) {
        row.put(header[i], cells[i]);
      }
      rows.put(cells[0], row);
    }
    return rows;
  }

  /** Inserts every fixture row as an active policy; returns case id to policy number. */
  Map<String, String> seedFixtures(String program) throws IOException {
    DateTimeFormatter yyyymmdd = DateTimeFormatter.BASIC_ISO_DATE;
    Map<String, String> policyByCase = new LinkedHashMap<>();
    List<Map<String, String>> rows = new ArrayList<>(fixtures(program).values());
    for (int i = 0; i < rows.size(); i++) {
      Map<String, String> row = rows.get(i);
      String pn = fixturePolicyNumber(i);
      insertPolicy(
          pn,
          row.get("policy_type"),
          "AC",
          LocalDate.parse(row.get("effective_date"), yyyymmdd),
          LocalDate.parse(row.get("expiry_date"), yyyymmdd));
      jdbc.update(
          "UPDATE pas.policies SET total_premium = ?::numeric, deductible = ?::numeric,"
              + " coverage_limit = ?::numeric WHERE policy_number = ?",
          row.get("total_premium"),
          row.get("deductible"),
          row.get("coverage_limit"),
          pn);
      policyByCase.put(row.get("case_id"), pn);
    }
    return policyByCase;
  }

  static List<String> reportTrailer(String reportPath) throws IOException {
    List<String> lines = Files.readAllLines(Path.of(reportPath));
    assertThat(lines.size()).isGreaterThanOrEqualTo(6);
    return lines.subList(lines.size() - 3, lines.size());
  }
}
