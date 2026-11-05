package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.rating.PremiumRater;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;

/**
 * One {@link PremiumRater#snapshot()} per job execution, taken before the first policy is rated, so
 * every partition of a run rates against the same rates and factors even if reference data changes
 * mid-run. The snapshot's {@link PremiumRater#version()} is kept in the job execution context; a
 * restart of the same instance takes a new snapshot and fails before rating anything if the rates
 * changed since the failed execution, because the committed chunks were rated with the old rates.
 * The operator then launches a same-day rerun, which re-rates the whole book.
 */
public class PremiumRunRaters implements JobExecutionListener {

  static final String RATING_VERSION = "pas.premium.rating-version";

  private final PremiumRater live;
  private final JobRepository jobs;
  private final Map<Long, PremiumRater> snapshots = new ConcurrentHashMap<>();

  public PremiumRunRaters(PremiumRater live, JobRepository jobs) {
    this.live = live;
    this.jobs = jobs;
  }

  @Override
  public void beforeJob(JobExecution execution) {
    PremiumRater snapshot = live.snapshot();
    String version = snapshot.version().orElseThrow();
    ExecutionContext context = execution.getExecutionContext();
    String rated = context.containsKey(RATING_VERSION) ? context.getString(RATING_VERSION) : null;
    if (rated != null && !rated.equals(version)) {
      throw new IllegalStateException(
          "rating reference data changed since the failed run of this business date; launch a"
              + " same-day rerun (rerun=<n>) to re-rate the whole book instead of restarting");
    }
    context.putString(RATING_VERSION, version);
    jobs.updateExecutionContext(execution);
    snapshots.put(execution.getId(), snapshot);
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
