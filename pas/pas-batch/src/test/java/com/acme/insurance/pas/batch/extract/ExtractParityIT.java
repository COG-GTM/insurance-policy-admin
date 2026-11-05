package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.persistence.ExtractRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Reference-run parity for AB-527: the SQL is read verbatim from the frozen jcl/DAILY-EXTRACT.jcl
 * and jcl/MONTHLY-EXPOSURE.jcl SYSIN, run on the same database (only {@code CURRENT DATE} is pinned
 * to the business date and the ACMEINS schema qualified), rendered with the feed layout, and the
 * bytes must equal the file the job delivered over SFTP, for 5 consecutive business dates with the
 * book changing between runs. This proves the selection logic and ordering; the comparison against
 * real mainframe output uses tools/extracts/extract_parity.py once FU-13 samples exist.
 */
@SpringBootTest(
    properties = {
      "spring.batch.job.enabled=false",
      "spring.batch.jdbc.initialize-schema=always",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration/postgresql"
    })
@Import(ExtractItSupport.Postgres.class)
class ExtractParityIT {

  static final ExtractItSupport.SftpServerFixture SFTP = new ExtractItSupport.SftpServerFixture();
  static final Path JCL = Path.of("../../jcl");
  static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 2);
  static final int CONSECUTIVE_RUNS = 5;

  @DynamicPropertySource
  static void sftp(DynamicPropertyRegistry registry) {
    SFTP.register(registry);
  }

  @Autowired JobLauncher launcher;

  @Autowired
  @Qualifier(ExtractJobsConfiguration.CLAIMS_JOB)
  Job claims;

  @Autowired
  @Qualifier(ExtractJobsConfiguration.ACTUARIAL_JOB)
  Job actuarial;

  @Autowired JdbcClient jdbc;

  @Test
  void fiveConsecutiveRunsMatchTheJclSqlByteForByte() throws Exception {
    List<String> sql = sysinStatements(JCL.resolve("DAILY-EXTRACT.jcl"));
    String exposureSql = sysinStatements(JCL.resolve("MONTHLY-EXPOSURE.jcl")).get(0);
    assertThat(sql).hasSize(2);
    ExtractFixtures fixtures = new ExtractFixtures(jdbc);
    fixtures.reset();
    fixtures.seedBook(FIRST_DAY);

    List<String> matched = new ArrayList<>();
    for (int run = 0; run < CONSECUTIVE_RUNS; run++) {
      LocalDate day = FIRST_DAY.plusDays(run);
      changeBook(fixtures, run, day);

      runJob(claims, day);
      assertSame(
          day,
          "claims/import/policy_extract.dat",
          reference(ExtractFeed.CLAIMS_POLICY, legacy(sql.get(0), day)));
      assertSame(
          day,
          "claims/import/coverage_extract.dat",
          reference(ExtractFeed.CLAIMS_COVERAGE, legacy(sql.get(1), day)));

      runJob(actuarial, day);
      assertSame(
          day,
          "actuarial/data/exposure_monthly.csv",
          reference(ExtractFeed.ACTUARIAL_EXPOSURE, exposureInJavaOrder(legacy(exposureSql, day))));
      matched.add(day.toString());
    }
    assertThat(matched).hasSize(CONSECUTIVE_RUNS);
  }

  /** Each day the book changes in a way the delta, the dump or the aggregation must notice. */
  private static void changeBook(ExtractFixtures f, int run, LocalDate day) {
    switch (run) {
      case 0 -> {}
      case 1 -> {
        f.policy(
            "POL000000006",
            "HOM",
            "AC",
            "NE02",
            "AG0004",
            ExtractFixtures.big("640.00"),
            12,
            day.atTime(8, 0));
        f.coverage(
            "POL000000006",
            1,
            "DWEL",
            "Dwelling, brick",
            ExtractFixtures.big("410000.00"),
            ExtractFixtures.big("640.00"),
            "AC",
            "CT0002",
            "H2001");
      }
      case 2 -> {
        f.policyStatus("POL000000003", "AC", day.atTime(0, 0));
        f.coverageStatus("POL000000001", 2, "CN");
      }
      case 3 -> {
        f.policyStatus("POL000000002", "CN", day.atTime(23, 59, 59));
        f.touch("POL000000005", day.minusDays(1).atStartOfDay().minusNanos(1000));
      }
      case 4 -> f.touch("POL000000001", day.minusDays(1).atStartOfDay());
      default -> throw new IllegalArgumentException("run " + run);
    }
  }

  private void runJob(Job job, LocalDate day) throws Exception {
    BatchStatus status =
        launcher
            .run(
                job,
                new JobParametersBuilder()
                    .addString(BusinessDateParameters.BUSINESS_DATE, day.toString())
                    .toJobParameters())
            .getStatus();
    assertThat(status).as(job.getName() + " " + day).isEqualTo(BatchStatus.COMPLETED);
  }

  private static void assertSame(LocalDate day, String remote, byte[] expected) throws IOException {
    byte[] actual = Files.readAllBytes(SFTP.root.resolve(remote));
    assertThat(actual).as(remote + " for business date " + day).isEqualTo(expected);
  }

  /** The JCL statement with only CURRENT DATE pinned and the schema qualified. */
  private List<ExtractRow> legacy(String statement, LocalDate day) {
    String sql =
        statement
            .replace("CURRENT DATE - 1 DAY", "(DATE '" + day + "' - INTERVAL '1 day')")
            .replaceAll("\\b(FROM|JOIN)\\s+(POLICIES|COVERAGES)\\b", "$1 ACMEINS.$2");
    List<ExtractRow> rows = new ArrayList<>();
    jdbc.sql(sql)
        .query(
            (java.sql.ResultSet rs) -> {
              rows.add(ExtractRow.of(rs));
            });
    return rows;
  }

  /**
   * The JCL orders exposure rows by POLICY_TYPE, BRANCH_CODE only (Q-34). Within those keys the
   * mainframe order is undefined, so the reference is sorted by the remaining group columns the way
   * the new job writes them; the order of the two legacy keys is checked, not changed.
   */
  private static List<ExtractRow> exposureInJavaOrder(List<ExtractRow> rows) {
    Comparator<ExtractRow> byLegacyKeys =
        Comparator.comparing((ExtractRow r) -> (String) r.get("POLICY_TYPE"))
            .thenComparing(
                r -> (String) r.get("BRANCH_CODE"),
                Comparator.nullsLast(Comparator.naturalOrder()));
    for (int i = 1; i < rows.size(); i++) {
      assertThat(byLegacyKeys.compare(rows.get(i - 1), rows.get(i))).isLessThanOrEqualTo(0);
    }
    Comparator<ExtractRow> full = byLegacyKeys;
    for (String column : List.of("COVERAGE_TYPE", "RATING_TERRITORY", "CLASS_CODE")) {
      full =
          full.thenComparing(
              r -> (String) r.get(column), Comparator.nullsLast(Comparator.naturalOrder()));
    }
    return rows.stream().sorted(full).toList();
  }

  private static byte[] reference(ExtractFeed feed, List<ExtractRow> rows) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FeedLayout layout = feed.layout();
    if (layout.header() != null) {
      out.writeBytes((layout.header() + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }
    for (ExtractRow row : rows) {
      out.writeBytes((layout.render(row) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }
    return out.toByteArray();
  }

  /** SQL statements of the //SYSIN DD * blocks, without the trailing semicolon. */
  static List<String> sysinStatements(Path jcl) throws IOException {
    String text = Files.readString(jcl, StandardCharsets.US_ASCII);
    Matcher m =
        Pattern.compile("(?m)^//SYSIN\\s+DD \\*\\R(.*?)^/\\*", Pattern.DOTALL).matcher(text);
    List<String> statements = new ArrayList<>();
    while (m.find()) {
      statements.add(m.group(1).strip().replaceAll(";$", ""));
    }
    return statements;
  }
}
