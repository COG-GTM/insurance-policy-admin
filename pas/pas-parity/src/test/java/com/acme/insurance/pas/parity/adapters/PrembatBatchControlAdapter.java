package com.acme.insurance.pas.parity.adapters;

import com.acme.insurance.pas.batch.PasBatchApplication;
import com.acme.insurance.pas.batch.premium.PremiumJobAlerting;
import com.acme.insurance.pas.batch.premium.PremiumJobConfiguration;
import com.acme.insurance.pas.batch.premium.PremiumJobParameters;
import com.acme.insurance.pas.parity.Effect;
import com.acme.insurance.pas.parity.FixtureCase;
import com.acme.insurance.pas.parity.ProgramAdapter;
import com.acme.insurance.pas.parity.RunResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Suite {@code prembat-batch-control}: loads the fixture policies into the reference database, runs
 * the real Spring Batch job {@code premiumRecalculation} (AB-523) of the pas-batch application and
 * reports one {@code SQL INSERT PREMIUMS} effect per policy outcome, in policy order: the stored
 * PREMIUMS row for an updated policy, the rejected write for an error. The return code maps the job
 * exit status the way PAS0050 did: 0 clean, 4 completed with policy errors, 8 failed.
 *
 * <p>Fixture write failures are reproduced in the database, not in Java: {@code
 * SQLCODE_INSERT_PREMIUMS -803} seeds the PREMIUMS row an earlier same-day run would have left, and
 * a term date a DATE column cannot hold is stored as the nearest valid day with a trigger rejecting
 * that policy's PREMIUMS write with SQLSTATE 22007 (DB2 -181).
 */
public final class PrembatBatchControlAdapter implements ProgramAdapter {

  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

  private static ConfigurableApplicationContext context;

  @Override
  public RunResult run(FixtureCase fixture) {
    ConfigurableApplicationContext app = context();
    JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
    reset(jdbc);
    List<String> rejected = new ArrayList<>();
    for (JsonNode row : fixture.input().path("ROWS")) {
      String number = Cobol.alphanumeric(fixture.text(row, "POLICY_NUMBER", ""), 12);
      String effective = fixture.text(row, "EFFECTIVE_DATE", "0");
      String expiry = fixture.text(row, "EXPIRY_DATE", "0");
      if (!isDate(effective) || !isDate(expiry)) {
        rejected.add(number);
      }
      jdbc.update(
          """
          INSERT INTO acmeins.policies (policy_number, policy_type, policy_status, effective_date,
            expiry_date, policyholder_id, total_premium, deductible, coverage_limit)
          VALUES (?, ?, 'AC', ?, ?, 'H000000001', CAST(? AS numeric), CAST(? AS numeric),
            CAST(? AS numeric))
          """,
          number,
          Cobol.alphanumeric(fixture.text(row, "POLICY_TYPE", ""), 3),
          nearestDate(effective),
          nearestDate(expiry),
          fixture.text(row, "TOTAL_PREMIUM", "0"),
          fixture.text(row, "DEDUCTIBLE", "0"),
          fixture.text(row, "COVERAGE_LIMIT", "0"));
      if ("-803".equals(fixture.text(row, "SQLCODE_INSERT_PREMIUMS", ""))) {
        jdbc.update(
            """
            INSERT INTO acmeins.premiums (policy_number, coverage_seq, term_effective_date,
              term_expiry_date, base_rate, territory_factor, class_factor, experience_mod,
              schedule_mod, discount_pct, surcharge_amt, tax_amt, total_premium, installment_code,
              calc_date, calc_by)
            VALUES (?, 1, ?, ?, 1, 1, 1, 1, 1, 0, 0, 0, 1.00, 'AN', ?, 'PREMBAT')
            """,
            number,
            nearestDate(effective),
            nearestDate(expiry),
            fixture.runDate());
      }
    }
    if (!rejected.isEmpty()) {
      jdbc.execute(
          "CREATE FUNCTION acmeins.parity_reject_premium() RETURNS trigger AS $$ BEGIN"
              + " IF NEW.policy_number IN ('"
              + String.join("','", rejected)
              + "') THEN RAISE EXCEPTION 'date value out of range' USING ERRCODE = '22007';"
              + " END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
      jdbc.execute(
          "CREATE TRIGGER parity_reject_premium BEFORE INSERT OR UPDATE ON acmeins.premiums"
              + " FOR EACH ROW EXECUTE FUNCTION acmeins.parity_reject_premium()");
    }
    try {
      JobExecution run =
          app.getBean(JobLauncher.class)
              .run(
                  app.getBean(PremiumJobConfiguration.JOB, Job.class),
                  new JobParametersBuilder()
                      .addString(PremiumJobParameters.BUSINESS_DATE, fixture.runDate().toString())
                      .addString("parity.case", fixture.id() + "@" + System.nanoTime())
                      .toJobParameters());
      return RunResult.completed(returnCode(run), effects(jdbc, run, fixture.runDate()));
    } catch (Exception e) {
      throw new IllegalStateException("premiumRecalculation could not be launched", e);
    } finally {
      reset(jdbc);
    }
  }

  private static List<Effect> effects(JdbcTemplate jdbc, JobExecution run, LocalDate runDate) {
    List<Effect> effects = new ArrayList<>();
    List<Map<String, Object>> outcomes =
        jdbc.queryForList(
            """
            SELECT i.policy_number, i.status, i.total_premium, i.error_code, i.detail,
                   p.effective_date, p.expiry_date
              FROM pasapp.premium_run_item i
              JOIN acmeins.policies p ON p.policy_number = i.policy_number
             WHERE i.job_instance_id = ?
             ORDER BY i.policy_number
            """,
            run.getJobInstance().getInstanceId());
    for (Map<String, Object> outcome : outcomes) {
      String number = outcome.get("policy_number").toString();
      if ("U".equals(outcome.get("status").toString().trim())) {
        Map<String, Object> row =
            jdbc.queryForMap(
                "SELECT * FROM acmeins.premiums WHERE policy_number = ? AND coverage_seq = 1",
                number);
        effects.add(
            premiumEffect(0, number, row.get("term_effective_date"), row.get("term_expiry_date"))
                .number("COVERAGE_SEQ", row.get("coverage_seq"))
                .number("BASE_RATE", row.get("base_rate"))
                .number("TERRITORY_FACTOR", row.get("territory_factor"))
                .number("CLASS_FACTOR", row.get("class_factor"))
                .number("EXPERIENCE_MOD", row.get("experience_mod"))
                .number("SCHEDULE_MOD", row.get("schedule_mod"))
                .number("DISCOUNT_PCT", row.get("discount_pct"))
                .number("SURCHARGE_AMT", row.get("surcharge_amt"))
                .number("TAX_AMT", row.get("tax_amt"))
                .number("TOTAL_PREMIUM", row.get("total_premium"))
                .text("INSTALLMENT_CODE", row.get("installment_code").toString())
                .text(
                    "CALC_DATE",
                    ((java.sql.Date) row.get("calc_date")).toLocalDate().format(YYYYMMDD))
                .text("CALC_BY", row.get("calc_by").toString())
                .build());
      } else {
        Effect.Builder rejected =
            premiumEffect(
                    sqlcode(outcome.get("error_code"), outcome.get("detail")),
                    number,
                    outcome.get("effective_date"),
                    outcome.get("expiry_date"))
                .text("INSTALLMENT_CODE", "AN")
                .text("CALC_DATE", runDate.format(YYYYMMDD))
                .text("CALC_BY", "PREMBAT");
        if (outcome.get("total_premium") != null) {
          rejected.number("TOTAL_PREMIUM", outcome.get("total_premium"));
        }
        effects.add(rejected.build());
      }
    }
    return effects;
  }

  private static Effect.Builder premiumEffect(
      int sqlcode, String number, Object effective, Object expiry) {
    return Effect.builder("SQL INSERT PREMIUMS")
        .sqlcode(sqlcode)
        .text("POLICY_NUMBER", number)
        .text("TERM_EFFECTIVE_DATE", ((java.sql.Date) effective).toLocalDate().format(YYYYMMDD))
        .text("TERM_EXPIRY_DATE", ((java.sql.Date) expiry).toLocalDate().format(YYYYMMDD));
  }

  /** The DB2 SQLCODE of the SQLSTATE the job recorded for a rejected write. */
  static int sqlcode(Object errorCode, Object detail) {
    String text = String.valueOf(detail);
    if ("DuplicateKeyException".equals(errorCode) || text.startsWith("SQLSTATE 23505")) {
      return -803;
    }
    if (text.startsWith("SQLSTATE 22007") || text.startsWith("SQLSTATE 22008")) {
      return -181;
    }
    return -1;
  }

  static int returnCode(JobExecution run) {
    if (run.getStatus() != BatchStatus.COMPLETED) {
      return 8;
    }
    return PremiumJobAlerting.COMPLETED_WITH_ERRORS.equals(run.getExitStatus().getExitCode())
        ? 4
        : ExitStatus.COMPLETED.getExitCode().equals(run.getExitStatus().getExitCode()) ? 0 : 8;
  }

  private static boolean isDate(String yyyymmdd) {
    try {
      LocalDate.parse(yyyymmdd, YYYYMMDD);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The date, or for a day the month does not have (20250229) the last day of that month. */
  static LocalDate nearestDate(String yyyymmdd) {
    if (isDate(yyyymmdd)) {
      return LocalDate.parse(yyyymmdd, YYYYMMDD);
    }
    YearMonth month =
        YearMonth.of(
            Integer.parseInt(yyyymmdd.substring(0, 4)), Integer.parseInt(yyyymmdd.substring(4, 6)));
    return month.atDay(Math.min(Integer.parseInt(yyyymmdd.substring(6, 8)), month.lengthOfMonth()));
  }

  private static void reset(JdbcTemplate jdbc) {
    jdbc.execute("DROP TRIGGER IF EXISTS parity_reject_premium ON acmeins.premiums");
    jdbc.execute("DROP FUNCTION IF EXISTS acmeins.parity_reject_premium()");
    jdbc.update("DELETE FROM pasapp.premium_run_item");
    jdbc.update("DELETE FROM acmeins.premiums");
    jdbc.update("DELETE FROM acmeins.policies");
  }

  private static synchronized ConfigurableApplicationContext context() {
    if (context == null) {
      PostgreSQLContainer<?> postgres = ReferenceDatabase.container();
      Map<String, Object> properties = new HashMap<>();
      properties.put("spring.datasource.url", postgres.getJdbcUrl());
      properties.put("spring.datasource.username", postgres.getUsername());
      properties.put("spring.datasource.password", postgres.getPassword());
      properties.put("spring.flyway.enabled", "false");
      properties.put("spring.batch.job.enabled", "false");
      properties.put("spring.main.banner-mode", "off");
      properties.put("pas.premium.partitions", "2");
      properties.put("pas.premium.chunk-size", "2");
      try {
        properties.put("pas.premium.report-dir", Files.createTempDirectory("premrpt").toString());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      // Command-line arguments outrank application.yml; builder properties would only be defaults.
      context =
          new SpringApplicationBuilder(PasBatchApplication.class)
              .web(WebApplicationType.NONE)
              .run(
                  properties.entrySet().stream()
                      .map(p -> "--" + p.getKey() + "=" + p.getValue())
                      .toArray(String[]::new));
    }
    return context;
  }
}
