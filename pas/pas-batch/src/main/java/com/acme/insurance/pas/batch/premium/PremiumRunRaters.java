package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.rating.PremiumRater;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * One {@link PremiumRater#snapshot()} per job execution, taken before the first policy is rated, so
 * every partition of a run rates against the same rates and factors even if reference data changes
 * mid-run. A restart takes a new snapshot.
 */
public class PremiumRunRaters implements JobExecutionListener {

  private final PremiumRater live;
  private final Map<Long, PremiumRater> snapshots = new ConcurrentHashMap<>();

  public PremiumRunRaters(PremiumRater live) {
    this.live = live;
  }

  @Override
  public void beforeJob(JobExecution execution) {
    snapshots.put(execution.getId(), live.snapshot());
  }

  @Override
  public void afterJob(JobExecution execution) {
    snapshots.remove(execution.getId());
  }

  PremiumRater forExecution(long jobExecutionId) {
    PremiumRater rater = snapshots.get(jobExecutionId);
    if (rater == null) {
      throw new IllegalStateException("no rating snapshot for job execution " + jobExecutionId);
    }
    return rater;
  }
}
