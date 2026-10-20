package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.common.domain.PremiumRecord;
import com.acme.insurance.pas.rating.PremiumRatingService;
import com.acme.insurance.pas.rating.RatingRequest;
import com.acme.insurance.pas.rating.RatingSnapshot;
import org.springframework.batch.item.ItemProcessor;

/**
 * PREMBAT {@code 3200-CALCULATE-PREMIUM} + the row built in {@code 3300-WRITE-PREMIUM-RECORD}, both
 * delegated to the shared rating component. Rating errors are table/data problems that affect the
 * whole book, so they are not skipped: they fail the run.
 */
public class PremiumRatingProcessor implements ItemProcessor<ActivePolicy, PremiumRecord> {

  /** CALC_BY written by PREMBAT; kept so PREMIUMS consumers see the same value. */
  public static final String CALC_BY = "PREMBAT";

  private final PremiumRatingService rating;
  private final RatingSnapshot snapshot;

  public PremiumRatingProcessor(PremiumRatingService rating, RatingSnapshot snapshot) {
    this.rating = rating;
    this.snapshot = snapshot;
  }

  @Override
  public PremiumRecord process(ActivePolicy policy) {
    return rating
        .rate(
            snapshot,
            RatingRequest.of(policy.policyType(), policy.effectiveDate(), policy.expiryDate()))
        .toPremiumRecord(policy.policyNumber(), CALC_BY);
  }
}
