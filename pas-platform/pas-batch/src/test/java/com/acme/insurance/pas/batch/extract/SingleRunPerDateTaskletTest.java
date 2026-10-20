package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

class SingleRunPerDateTaskletTest {

  private final JobExplorer explorer = mock(JobExplorer.class);
  private final JobInstance instance = new JobInstance(1L, "dailyExtractJob");

  private JobExecution execution(long id, LocalDate businessDate) {
    return new JobExecution(
        instance,
        id,
        new JobParametersBuilder()
            .addLocalDate(ExtractOutputs.BUSINESS_DATE, businessDate)
            .addString("launchedAt", "run-" + id)
            .toJobParameters());
  }

  private RepeatStatus run(JobExecution current) {
    ChunkContext context = new ChunkContext(new StepContext(new StepExecution("guard", current)));
    return new SingleRunPerDateTasklet(explorer).execute(null, context);
  }

  @Test
  void failsWhenSameBusinessDateIsAlreadyRunning() {
    JobExecution current = execution(2, LocalDate.of(2026, 10, 16));
    JobExecution running = execution(1, LocalDate.of(2026, 10, 16));
    when(explorer.findRunningJobExecutions("dailyExtractJob")).thenReturn(Set.of(current, running));

    assertThatThrownBy(() -> run(current))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("businessDate=2026-10-16")
        .hasMessageContaining("execution 1");
  }

  @Test
  void whenTwoSameDateRunsStartTogetherOnlyTheEarlierProceeds() {
    JobExecution earlier = execution(1, LocalDate.of(2026, 10, 16));
    JobExecution later = execution(2, LocalDate.of(2026, 10, 16));
    when(explorer.findRunningJobExecutions("dailyExtractJob")).thenReturn(Set.of(earlier, later));

    assertThat(run(earlier)).isEqualTo(RepeatStatus.FINISHED);
    assertThatThrownBy(() -> run(later)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void allowsOtherBusinessDatesAndItself() {
    JobExecution current = execution(2, LocalDate.of(2026, 10, 16));
    JobExecution otherDate = execution(1, LocalDate.of(2026, 10, 15));
    when(explorer.findRunningJobExecutions("dailyExtractJob"))
        .thenReturn(Set.of(current, otherDate));

    assertThat(run(current)).isEqualTo(RepeatStatus.FINISHED);
  }
}
