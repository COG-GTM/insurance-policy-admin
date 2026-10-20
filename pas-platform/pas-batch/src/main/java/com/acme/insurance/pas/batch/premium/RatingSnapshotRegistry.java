package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.rating.PremiumRatingService;
import com.acme.insurance.pas.rating.RatingSnapshot;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the rating tables for each running job execution. The tables are read once per run (AB-522
 * {@link PremiumRatingService#snapshot}) and shared, immutable, by every partition thread.
 */
public class RatingSnapshotRegistry {

  private final PremiumRatingService rating;
  private final Map<Long, RatingSnapshot> snapshots = new ConcurrentHashMap<>();

  public RatingSnapshotRegistry(PremiumRatingService rating) {
    this.rating = rating;
  }

  public RatingSnapshot load(long jobExecutionId, LocalDate ratingDate) {
    return snapshots.computeIfAbsent(jobExecutionId, id -> rating.snapshot(ratingDate));
  }

  public RatingSnapshot get(long jobExecutionId) {
    RatingSnapshot snapshot = snapshots.get(jobExecutionId);
    if (snapshot == null) {
      throw new IllegalStateException(
          "no rating snapshot loaded for job execution " + jobExecutionId);
    }
    return snapshot;
  }

  public void release(long jobExecutionId) {
    snapshots.remove(jobExecutionId);
  }
}
