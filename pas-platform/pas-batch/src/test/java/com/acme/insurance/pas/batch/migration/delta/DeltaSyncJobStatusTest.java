package com.acme.insurance.pas.batch.migration.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acme.insurance.pas.batch.migration.MigrationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class DeltaSyncJobStatusTest {
  @Test
  void pausedJobExitsDisabledWithoutRunningSync() throws Exception {
    DeltaSyncService syncService = mock(DeltaSyncService.class);
    MigrationProperties properties =
        new MigrationProperties(
            true,
            null,
            null,
            new MigrationProperties.DeltaSync(null, true, null, false, null),
            null,
            null,
            null);
    var step =
        new DeltaSyncJobConfiguration()
            .deltaSyncStep(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                syncService,
                properties);
    Tasklet tasklet = (Tasklet) ReflectionTestUtils.getField(step, "tasklet");
    StepExecution stepExecution = new StepExecution("deltaSyncStep", new JobExecution(1L));
    StepContribution contribution = new StepContribution(stepExecution);
    ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));

    RepeatStatus status = tasklet.execute(contribution, chunkContext);

    assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    assertThat(contribution.getExitStatus().getExitCode()).isEqualTo("DISABLED");
    verify(syncService, never()).sync();
  }
}
