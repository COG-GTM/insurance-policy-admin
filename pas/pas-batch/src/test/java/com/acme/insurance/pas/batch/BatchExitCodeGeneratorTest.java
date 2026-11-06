package com.acme.insurance.pas.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.MigrationJobsConfiguration;
import com.acme.insurance.pas.batch.premium.PremiumJobAlerting;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent;

class BatchExitCodeGeneratorTest {

  private static int exitCode(BatchStatus status, String exitStatus) {
    JobExecution execution = new JobExecution(1L);
    execution.setStatus(status);
    execution.setExitStatus(new ExitStatus(exitStatus));
    BatchExitCodeGenerator generator = new BatchExitCodeGenerator();
    generator.onApplicationEvent(new JobExecutionEvent(execution));
    return generator.getExitCode();
  }

  @Test
  void reconciliationDifferencesExitNonZero() {
    assertThat(exitCode(BatchStatus.COMPLETED, MigrationJobsConfiguration.DIFFERENCES))
        .isEqualTo(BatchExitCodeGenerator.DIFFERENCES);
  }

  @Test
  void cleanRunsAndPremiumWarningsExitZero() {
    assertThat(exitCode(BatchStatus.COMPLETED, "COMPLETED")).isZero();
    assertThat(exitCode(BatchStatus.COMPLETED, PremiumJobAlerting.COMPLETED_WITH_ERRORS)).isZero();
    assertThat(new BatchExitCodeGenerator().getExitCode()).isZero();
  }

  @Test
  void unfinishedJobsKeepSpringBootsBatchStatusCode() {
    assertThat(exitCode(BatchStatus.FAILED, "FAILED")).isEqualTo(BatchStatus.FAILED.ordinal());
    assertThat(exitCode(BatchStatus.STOPPED, "STOPPED")).isEqualTo(BatchStatus.STOPPED.ordinal());
  }
}
