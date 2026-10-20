package com.acme.insurance.pas.batch.premium;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;

/**
 * PREMBAT report totals: TOTAL POLICIES READ / UPDATED / ERRORS. Summed over every worker partition
 * of every execution of the job instance, so a restarted run reports the whole book. Only committed
 * outcomes are counted (a failed chunk's reads are re-read on restart): like PREMBAT, every policy
 * read is either updated or an error, so READ = UPDATED + ERRORS.
 */
public record PremiumRunTotals(long read, long updated, long errors) {

  public static final PremiumRunTotals ZERO = new PremiumRunTotals(0, 0, 0);

  public static PremiumRunTotals of(JobExplorer explorer, JobInstance instance) {
    long updated = 0;
    long filtered = 0;
    long errors = 0;
    for (JobExecution execution : explorer.getJobExecutions(instance)) {
      for (StepExecution step : execution.getStepExecutions()) {
        if (!step.getStepName().startsWith(PremiumRecalculationJobConfig.WORKER_STEP + ":")) {
          continue;
        }
        updated += step.getWriteCount();
        errors += step.getReadSkipCount() + step.getProcessSkipCount() + step.getWriteSkipCount();
        filtered += step.getFilterCount();
      }
    }
    return new PremiumRunTotals(updated + errors + filtered, updated, errors);
  }
}
