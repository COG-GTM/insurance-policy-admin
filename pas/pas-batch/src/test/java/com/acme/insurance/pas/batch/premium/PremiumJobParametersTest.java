package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

class PremiumJobParametersTest {

  // 04:30 UTC on 2 March is still 1 March in New York, the zone PAS0050 ran in.
  static final Clock CLOCK = Clock.fixed(Instant.parse("2025-03-02T04:30:00Z"), ZoneOffset.UTC);
  static final ZoneId EASTERN = ZoneId.of("America/New_York");

  final PremiumJobParameters parameters =
      new PremiumJobParameters(CLOCK, EASTERN, () -> null, date -> List.of());

  @Test
  void theScheduledRunRatesTheEasternBusinessDate() {
    JobParameters next = parameters.getNext(null);
    assertThat(next.getString("businessDate")).isEqualTo("2025-03-01");
    assertThat(next.getLong("rerun")).isNull();
  }

  @Test
  void aNewRunOfACompletedBusinessDateIsANumberedRerun() {
    JobParameters first = parameters.getNext(null);
    JobParameters rerun = parameters.getNext(first);
    assertThat(rerun.getLong("rerun")).isEqualTo(1L);
    assertThat(parameters.getNext(rerun).getLong("rerun")).isEqualTo(2L);
    JobParameters yesterday =
        new JobParametersBuilder()
            .addString("businessDate", "2025-02-28")
            .addLong("rerun", 4L)
            .toJobParameters();
    assertThat(parameters.getNext(yesterday).getLong("rerun")).isNull();
  }

  @Test
  void rejectsAMissingOrInvalidDateAndAMissingReportDirectory() {
    assertThatThrownBy(() -> parameters.validate(new JobParameters()))
        .isInstanceOf(JobParametersInvalidException.class);
    assertThatThrownBy(
            () ->
                parameters.validate(
                    new JobParametersBuilder()
                        .addString("businessDate", "2025-02-29")
                        .toJobParameters()))
        .hasMessageContaining("YYYY-MM-DD");
    PremiumJobParameters unconfigured =
        new PremiumJobParameters(
            CLOCK, EASTERN, () -> "pas.premium.report-dir is not set", date -> List.of());
    assertThatThrownBy(() -> unconfigured.validate(parameters.getNext(null)))
        .hasMessageContaining("report-dir");
  }

  @Test
  void todaysLatestExecutionIsRestartedWhenItFailedOrStopped() {
    for (BatchStatus status : List.of(BatchStatus.FAILED, BatchStatus.STOPPED)) {
      PremiumJobParameters withHistory =
          history(
              "2025-03-01",
              run(1L, 2L, "2025-03-01", 1L, status),
              run(2L, 1L, "2025-03-01", null, BatchStatus.COMPLETED));

      assertThat(withHistory.restartOfToday())
          .as(status.name())
          .contains(identifying("2025-03-01", 1L));
    }
  }

  @Test
  void anOlderInstanceRestartedLastIsTodaysLatestExecution() {
    // Instance 1 failed, rerun instance 2 completed, then instance 1 was restarted and failed
    // again.
    PremiumJobParameters withHistory =
        history(
            "2025-03-01",
            run(2L, 2L, "2025-03-01", 1L, BatchStatus.COMPLETED),
            run(1L, 3L, "2025-03-01", null, BatchStatus.FAILED));

    assertThat(withHistory.restartOfToday()).contains(identifying("2025-03-01", null));
    assertThat(withHistory.getNext(null)).isEqualTo(identifying("2025-03-01", 2L));
  }

  @Test
  void nothingIsRestartedAfterACompletedOrKilledRunOrOnAFreshDay() {
    for (BatchStatus status :
        List.of(
            BatchStatus.COMPLETED,
            BatchStatus.ABANDONED,
            BatchStatus.STARTED,
            BatchStatus.UNKNOWN)) {
      PremiumJobParameters withHistory =
          history(
              "2025-03-01",
              run(3L, 3L, "2025-03-01", 1L, status),
              run(1L, 1L, "2025-03-01", null, BatchStatus.FAILED));

      assertThat(withHistory.restartOfToday()).as(status.name()).isEmpty();
    }
    assertThat(parameters.restartOfToday()).isEmpty();
  }

  @Test
  void theNextInstanceIsAlwaysANewRerunEvenAfterAFailure() {
    for (BatchStatus status : BatchStatus.values()) {
      PremiumJobParameters withHistory =
          history(
              "2025-03-01",
              run(3L, 3L, "2025-03-01", 1L, status),
              run(2L, 2L, "2025-03-01", 2L, BatchStatus.COMPLETED),
              run(1L, 1L, "2025-03-01", null, BatchStatus.FAILED));

      JobParameters next =
          withHistory.getNext(run(9L, 9L, "2025-02-28", 7L, status).getJobParameters());

      assertThat(next).as(status.name()).isEqualTo(identifying("2025-03-01", 3L));
    }
  }

  private static PremiumJobParameters history(String businessDate, JobExecution... runs) {
    return new PremiumJobParameters(
        CLOCK, EASTERN, () -> null, date -> businessDate.equals(date) ? List.of(runs) : List.of());
  }

  private static JobParameters identifying(String businessDate, Long rerun) {
    JobParametersBuilder builder =
        new JobParametersBuilder().addString("businessDate", businessDate);
    if (rerun != null) {
      builder.addLong("rerun", rerun);
    }
    return builder.toJobParameters();
  }

  private static JobExecution run(
      long instanceId, long executionId, String businessDate, Long rerun, BatchStatus status) {
    JobParameters params =
        new JobParametersBuilder(identifying(businessDate, rerun))
            .addLong("launch.nonIdentifying", executionId, false)
            .toJobParameters();
    JobExecution execution =
        new JobExecution(
            new JobInstance(instanceId, PremiumJobConfiguration.JOB), executionId, params);
    execution.setStatus(status);
    return execution;
  }
}
