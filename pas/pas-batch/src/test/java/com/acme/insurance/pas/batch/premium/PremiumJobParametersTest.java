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
  void aLaunchWithoutArgumentsRestartsTodaysFailedOrStoppedRun() {
    for (BatchStatus status : List.of(BatchStatus.FAILED, BatchStatus.STOPPED)) {
      JobExecution failed = run(2L, "2025-03-01", 1L, status);
      PremiumJobParameters withHistory =
          new PremiumJobParameters(
              CLOCK,
              EASTERN,
              () -> null,
              date ->
                  "2025-03-01".equals(date)
                      ? List.of(failed, run(1L, "2025-03-01", null, BatchStatus.COMPLETED))
                      : List.of());

      JobParameters next = withHistory.getNext(failed.getJobParameters());

      assertThat(next).as(status.name()).isEqualTo(identifying("2025-03-01", 1L));
    }
  }

  @Test
  void aLaunchWithoutArgumentsAfterACompletedOrKilledRunIsTheNextRerun() {
    for (BatchStatus status :
        List.of(
            BatchStatus.COMPLETED,
            BatchStatus.ABANDONED,
            BatchStatus.STARTED,
            BatchStatus.UNKNOWN)) {
      PremiumJobParameters withHistory =
          new PremiumJobParameters(
              CLOCK,
              EASTERN,
              () -> null,
              date ->
                  List.of(
                      run(3L, "2025-03-01", 1L, status),
                      run(2L, "2025-03-01", 2L, BatchStatus.COMPLETED),
                      run(1L, "2025-03-01", null, BatchStatus.FAILED)));

      JobParameters next =
          withHistory.getNext(run(9L, "2025-02-28", 7L, status).getJobParameters());

      assertThat(next).as(status.name()).isEqualTo(identifying("2025-03-01", 3L));
    }
  }

  private static JobParameters identifying(String businessDate, Long rerun) {
    JobParametersBuilder builder =
        new JobParametersBuilder().addString("businessDate", businessDate);
    if (rerun != null) {
      builder.addLong("rerun", rerun);
    }
    return builder.toJobParameters();
  }

  private static JobExecution run(long id, String businessDate, Long rerun, BatchStatus status) {
    JobParameters params =
        new JobParametersBuilder(identifying(businessDate, rerun))
            .addLong("launch.nonIdentifying", id, false)
            .toJobParameters();
    JobExecution execution =
        new JobExecution(new JobInstance(id, PremiumJobConfiguration.JOB), id, params);
    execution.setStatus(status);
    return execution;
  }
}
