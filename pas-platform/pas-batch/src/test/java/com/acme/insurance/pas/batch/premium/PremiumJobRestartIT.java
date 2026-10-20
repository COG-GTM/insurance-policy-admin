package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Restart from the point of failure, rerun replacement (Q-22) and partial-failure reporting. */
@TestPropertySource(properties = "pas.batch.premium.chunk-size=10")
class PremiumJobRestartIT extends PremiumJobTestSupport {

  @Autowired JobExplorer explorer;

  static final int BOOK = 400;

  @BeforeEach
  void book() {
    resetBook();
    for (int i = 0; i < BOOK; i++) {
      insertPolicy(
          String.format("RST%09d", i),
          i % 2 == 0 ? "AUT" : "HOM",
          "AC",
          LocalDate.of(2026, 1, 1),
          LocalDate.of(2027, 1, 1));
    }
    // counts every INSERT/UPDATE the job makes, per policy, to prove exactly-once processing
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS public.premium_writes (policy_number CHAR(12), op TEXT);
        TRUNCATE public.premium_writes;
        CREATE TABLE IF NOT EXISTS public.premium_fail (policy_number CHAR(12));
        TRUNCATE public.premium_fail;
        CREATE OR REPLACE FUNCTION public.premium_write_probe() RETURNS trigger AS $$
        BEGIN
          IF EXISTS (SELECT 1 FROM public.premium_fail f WHERE f.policy_number = NEW.policy_number)
          THEN RAISE EXCEPTION 'injected failure for %', NEW.policy_number;
          END IF;
          INSERT INTO public.premium_writes VALUES (NEW.policy_number, TG_OP);
          RETURN NEW;
        END $$ LANGUAGE plpgsql;
        DROP TRIGGER IF EXISTS premium_write_probe ON pas.premiums;
        CREATE TRIGGER premium_write_probe BEFORE INSERT OR UPDATE ON pas.premiums
          FOR EACH ROW EXECUTE FUNCTION public.premium_write_probe();
        """);
  }

  @AfterEach
  void dropProbe() {
    jdbc.execute("DROP TRIGGER IF EXISTS premium_write_probe ON pas.premiums");
  }

  @Test
  void failedRunRestartsFromLastCommitAndProcessesRemainderExactlyOnce() throws Exception {
    LocalDate runDate = LocalDate.of(2026, 10, 21);
    jdbc.update("INSERT INTO public.premium_fail VALUES ('RST000000250')");

    JobExecution failed = run(runDate, false);

    assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
    PremiumRunSummary failedSummary = summaries.find(failed.getId()).orElseThrow();
    assertThat(failedSummary.outcome()).isEqualTo(PremiumRunOutcome.FAILED);
    assertThat(failedSummary.failureMessage()).isNotBlank();
    int writtenBeforeRestart = count("SELECT COUNT(*) FROM pas.premiums");
    assertThat(writtenBeforeRestart).isBetween(1, BOOK - 1);

    jdbc.update("TRUNCATE public.premium_fail");
    JobExecution restarted = run(runDate, false);

    assertThat(restarted.getJobInstance().getInstanceId())
        .isEqualTo(failed.getJobInstance().getInstanceId());
    assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    // only the failed partition ran again (as recorded in the job repository)
    List<String> rerunPartitions =
        explorer.getJobExecution(restarted.getId()).getStepExecutions().stream()
            .map(StepExecution::getStepName)
            .filter(n -> n.startsWith(PremiumRecalculationJobConfig.WORKER_STEP + ":"))
            .toList();
    assertThat(rerunPartitions).hasSize(1);
    assertThat(count("SELECT COUNT(*) FROM pas.premiums")).isEqualTo(BOOK);
    // every policy written exactly once across both executions (committed writes only)
    assertThat(count("SELECT COUNT(*) FROM public.premium_writes")).isEqualTo(BOOK);
    assertThat(count("SELECT COUNT(DISTINCT policy_number) FROM public.premium_writes"))
        .isEqualTo(BOOK);
    PremiumRunSummary summary = summaries.find(restarted.getId()).orElseThrow();
    assertThat(summary.totals()).isEqualTo(new PremiumRunTotals(BOOK, BOOK, 0));
    assertThat(reportTrailer(summary.reportPath()))
        .containsExactly(
            "TOTAL POLICIES READ:    0000400",
            "TOTAL POLICIES UPDATED: 0000400",
            "TOTAL ERRORS:           0000000");
    assertThat(
            java.nio.file.Files.readAllLines(java.nio.file.Path.of(summary.reportPath())).stream()
                .filter(l -> l.startsWith("RST"))
                .count())
        .isEqualTo(BOOK);
  }

  @Test
  void rerunOfACompletedDateReplacesRowsInsteadOfFailing() throws Exception {
    LocalDate runDate = LocalDate.of(2026, 10, 22);
    assertThat(run(runDate, false).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    // a rate change between the runs must show up in the replaced rows
    jdbc.update("UPDATE pas.premiums SET total_premium = 0, calc_by = 'OLD'");
    jdbc.update("DELETE FROM public.premium_writes WHERE op IS NOT NULL");

    JobExecution rerun = run(runDate, true);

    assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(count("SELECT COUNT(*) FROM pas.premiums")).isEqualTo(BOOK);
    assertThat(count("SELECT COUNT(*) FROM pas.premiums WHERE calc_by = 'PREMBAT'"))
        .isEqualTo(BOOK);
    assertThat(count("SELECT COUNT(*) FROM public.premium_writes WHERE op = 'UPDATE'"))
        .isEqualTo(BOOK);
    Map<String, Object> aut =
        jdbc.queryForMap("SELECT * FROM pas.premiums WHERE policy_number = 'RST000000000'");
    assertThat((BigDecimal) aut.get("total_premium")).isEqualByComparingTo("904.75");
    assertThat(summaries.find(rerun.getId()).orElseThrow().totals())
        .isEqualTo(new PremiumRunTotals(BOOK, BOOK, 0));
  }

  @Test
  void writeErrorsAreCountedAndTheRunCompletesWithErrors() throws Exception {
    LocalDate runDate = LocalDate.of(2026, 10, 23);
    // a CHECK violation is a data-integrity error: PREMBAT counts it and carries on (RC 4)
    jdbc.execute(
        "ALTER TABLE pas.premiums ADD CONSTRAINT premium_check_violation"
            + " CHECK (policy_number NOT IN ('RST000000007', 'RST000000300')) NOT VALID");
    try {
      JobExecution execution = run(runDate, false);

      assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
      assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_ERRORS");
      assertThat(PremiumRunOutcome.of(execution).exitCode()).isEqualTo(4);
      PremiumRunSummary summary = summaries.find(execution.getId()).orElseThrow();
      assertThat(summary.totals()).isEqualTo(new PremiumRunTotals(BOOK, BOOK - 2, 2));
      assertThat(count("SELECT COUNT(*) FROM pas.premiums")).isEqualTo(BOOK - 2);
    } finally {
      jdbc.execute("ALTER TABLE pas.premiums DROP CONSTRAINT premium_check_violation");
    }
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }
}
