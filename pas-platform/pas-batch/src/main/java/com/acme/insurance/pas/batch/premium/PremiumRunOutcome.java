package com.acme.insurance.pas.batch.premium;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;

/**
 * Outcome of a run and its process exit code. PREMBAT ended RC 4 when any policy failed and was
 * meant to end RC 16 when it could not run (lost by Q-25); the Java job keeps those codes.
 */
public enum PremiumRunOutcome {
  COMPLETED(0),
  COMPLETED_WITH_ERRORS(4),
  FAILED(16);

  /** Exit status of a run that completed but could not write some PREMIUMS rows. */
  public static final ExitStatus COMPLETED_WITH_ERRORS_STATUS =
      new ExitStatus("COMPLETED_WITH_ERRORS", "Some PREMIUMS rows could not be written");

  private final int exitCode;

  PremiumRunOutcome(int exitCode) {
    this.exitCode = exitCode;
  }

  public int exitCode() {
    return exitCode;
  }

  public static PremiumRunOutcome of(JobExecution execution) {
    if (execution.getStatus() != BatchStatus.COMPLETED) {
      return FAILED;
    }
    return COMPLETED_WITH_ERRORS_STATUS
            .getExitCode()
            .equals(execution.getExitStatus().getExitCode())
        ? COMPLETED_WITH_ERRORS
        : COMPLETED;
  }
}
