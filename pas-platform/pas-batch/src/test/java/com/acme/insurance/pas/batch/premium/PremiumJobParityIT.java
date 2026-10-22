package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityHarness;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestFactory;
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
    properties = {
      "spring.main.web-application-type=none",
      "pas.routing.premium-batch=new",
      "pas.batch.premium.grid-size=2",
      "pas.batch.premium.concurrency=1",
      "pas.batch.premium.chunk-size=1",
      "pas.batch.premium.report-dir=${java.io.tmpdir}/pas-batch-premium-parity-it"
    })
class PremiumJobParityIT {
  private static final Path REPORT_ROOT =
      Path.of(System.getProperty("java.io.tmpdir"), "pas-batch-premium-parity-it");

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

  @BeforeEach
  void resetTables() {
    jdbcTemplate.update("DELETE FROM pas.premiums");
    jdbcTemplate.update("DELETE FROM pas.policies");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_item");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_summary");
  }

  @TestFactory
  Stream<org.junit.jupiter.api.DynamicTest> premBatFixturesMatchJobParity() {
    return ParityHarness.dynamicTests(new PremiumAdapter());
  }

  private final class PremiumAdapter
      implements ParityAdapter<PremiumAdapter.Input, PremiumAdapter.Output> {
    @Override
    public String program() {
      return "PREMBAT";
    }

    @Override
    public String scope() {
      return "job";
    }

    @Override
    public Class<Input> inputType() {
      return Input.class;
    }

    @Override
    public Output run(Input input) {
      LocalDate businessDate =
          LocalDate.parse(input.currentDate(), DateTimeFormatter.BASIC_ISO_DATE);
      jdbcTemplate.update("DELETE FROM pas.premiums");
      jdbcTemplate.update("DELETE FROM pas.policies");
      jdbcTemplate.update("DELETE FROM pas_batch.premium_run_item");
      jdbcTemplate.update("DELETE FROM pas_batch.premium_run_summary");
      for (Policy policy : input.policies()) {
        LocalDate effectiveDate =
            LocalDate.parse(policy.effectiveDate(), DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate expiryDate =
            LocalDate.parse(policy.expiryDate(), DateTimeFormatter.BASIC_ISO_DATE);
        jdbcTemplate.update(
            "INSERT INTO pas.policies "
                + "(policy_number, policy_type, policy_status, effective_date, expiry_date, "
                + "policyholder_id, total_premium, deductible, coverage_limit) "
                + "VALUES (?, ?, 'AC', ?, ?, 'HOLDER0001', ?, ?, ?)",
            policy.policyNumber(),
            policy.policyType(),
            effectiveDate,
            expiryDate,
            policy.totalPremium(),
            policy.deductible(),
            policy.coverageLimit());
        if (policy.insertSqlcode() == -803) {
          jdbcTemplate.update(
              "INSERT INTO pas.premiums "
                  + "(policy_number, coverage_seq, term_effective_date, term_expiry_date) "
                  + "VALUES (?, 1, ?, ?)",
              policy.policyNumber(),
              effectiveDate,
              expiryDate);
        }
      }
      JobExecution execution;
      try {
        execution =
            jobLauncher.run(
                premiumJob,
                new JobParametersBuilder()
                    .addString("businessDate", businessDate.toString(), true)
                    .addLong("rerun", System.nanoTime(), true)
                    .toJobParameters());
      } catch (Exception exception) {
        throw new IllegalStateException("Unable to run PREMBAT job parity fixture", exception);
      }
      List<PremiumOutput> premiumRows =
          jdbcTemplate.query(
              "SELECT policy_number, coverage_seq, term_effective_date, term_expiry_date, "
                  + "base_rate, tax_amt, surcharge_amt, total_premium, calc_date, calc_by "
                  + "FROM pas.premiums ORDER BY policy_number",
              (rs, row) ->
                  new PremiumOutput(
                      rs.getString("policy_number").trim(),
                      rs.getInt("coverage_seq"),
                      rs.getDate("term_effective_date")
                          .toLocalDate()
                          .format(DateTimeFormatter.BASIC_ISO_DATE),
                      rs.getDate("term_expiry_date")
                          .toLocalDate()
                          .format(DateTimeFormatter.BASIC_ISO_DATE),
                      rs.getBigDecimal("base_rate"),
                      rs.getBigDecimal("tax_amt"),
                      rs.getBigDecimal("surcharge_amt"),
                      rs.getBigDecimal("total_premium"),
                      rs.getDate("calc_date")
                          .toLocalDate()
                          .format(DateTimeFormatter.BASIC_ISO_DATE),
                      rs.getString("calc_by").trim()));
      Summary summary =
          jdbcTemplate.queryForObject(
              "SELECT policies_read, policies_updated, policies_error "
                  + "FROM pas_batch.premium_run_summary WHERE job_execution_id = ?",
              (rs, row) ->
                  new Summary(
                      rs.getInt("policies_read"),
                      rs.getInt("policies_updated"),
                      rs.getInt("policies_error")),
              execution.getId());
      int returnCode = summary.errors() == 0 ? 0 : 4;
      assertGoldenReport(input, businessDate, execution.getId(), summary);
      return new Output(
          premiumRows,
          List.of(new JobEnd(returnCode, summary.read(), summary.updated(), summary.errors())));
    }

    private void assertGoldenReport(
        Input input, LocalDate date, long executionId, Summary summary) {
      try {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("parity/golden/PREMBAT"))) {
          root = root.getParent();
        }
        if (root == null) {
          throw new IllegalStateException("Unable to locate PREMBAT golden reports");
        }
        ObjectMapper mapper = new ObjectMapper();
        Path goldenPath;
        try (Stream<Path> goldens = Files.list(root.resolve("parity/golden/PREMBAT"))) {
          goldenPath =
              goldens
                  .filter(path -> path.getFileName().toString().endsWith(".json"))
                  .filter(
                      path -> {
                        try {
                          JsonNode events =
                              mapper
                                  .readTree(path.toFile())
                                  .path("trace")
                                  .path("events")
                                  .path("report");
                          if (input.policies().isEmpty()) {
                            return events.size() == 6;
                          }
                          return events.size() > 2
                              && events
                                  .get(2)
                                  .asText()
                                  .startsWith(input.policies().getFirst().policyNumber());
                        } catch (Exception exception) {
                          throw new IllegalStateException(exception);
                        }
                      })
                  .findFirst()
                  .orElseThrow();
        }
        List<String> golden =
            mapper
                .readTree(goldenPath.toFile())
                .path("trace")
                .path("events")
                .path("report")
                .valueStream()
                .map(JsonNode::asText)
                .toList();
        Path report =
            REPORT_ROOT
                .resolve("premiumRecalculationJob")
                .resolve(date.toString())
                .resolve(Long.toString(executionId))
                .resolve("PREMRPT.D" + date.format(DateTimeFormatter.ofPattern("yyMMdd")) + ".txt");
        List<String> actual = Files.readAllLines(report, StandardCharsets.ISO_8859_1);
        assertThat(actual.getFirst()).isEqualTo(reportRecord(golden.getFirst()));
        assertThat(actual.get(1)).isEqualTo(reportRecord(golden.get(1)));
        List<String> footer = actual.subList(actual.size() - 4, actual.size());
        if (input.policies().stream().anyMatch(policy -> policy.insertSqlcode() != 0)) {
          assertThat(footer)
              .containsExactly(
                  reportRecord(golden.get(golden.size() - 4)),
                  reportRecord("TOTAL POLICIES READ:    " + count(summary.read())),
                  reportRecord("TOTAL POLICIES UPDATED: " + count(summary.updated())),
                  reportRecord("TOTAL ERRORS:           " + count(summary.errors())));
        } else {
          assertThat(footer)
              .isEqualTo(
                  golden.subList(golden.size() - 4, golden.size()).stream()
                      .map(this::reportRecord)
                      .toList());
        }
        assertThat(actual).allMatch(line -> line.length() == 132);
        assertThat(actual.get(2))
            .startsWith(
                input.policies().isEmpty()
                    ? ""
                    : input.policies().getFirst().policyNumber() + " PREMIUM: ");
        assertThat(summary.read()).isEqualTo(input.policies().size());
      } catch (Exception exception) {
        throw new AssertionError("PREMRPT report does not match its COBOL golden shape", exception);
      }
    }

    private String reportRecord(String value) {
      return (value + " ".repeat(132)).substring(0, 132);
    }

    private String count(long value) {
      return String.format(Locale.ROOT, "%07d", value);
    }

    record Input(String currentDate, List<Policy> policies) {}

    record Policy(
        String policyNumber,
        String policyType,
        BigDecimal totalPremium,
        BigDecimal deductible,
        BigDecimal coverageLimit,
        String effectiveDate,
        String expiryDate,
        int insertSqlcode) {}

    record Output(List<PremiumOutput> premium, List<JobEnd> jobEnd) {}

    record PremiumOutput(
        String policyNumber,
        int coverageSeq,
        String termEffectiveDate,
        String termExpiryDate,
        BigDecimal baseRate,
        BigDecimal taxAmount,
        BigDecimal surchargeAmount,
        BigDecimal totalPremium,
        String calcDate,
        String calcBy) {}

    record JobEnd(int returnCode, int policiesRead, int policiesUpdated, int policiesError) {}

    record Summary(int read, int updated, int errors) {}
  }
}
