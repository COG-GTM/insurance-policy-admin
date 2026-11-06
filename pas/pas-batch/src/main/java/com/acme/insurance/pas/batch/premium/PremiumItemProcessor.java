package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.persistence.ActivePolicy;
import com.acme.insurance.pas.rating.PremiumRater;
import com.acme.insurance.pas.rating.RatingRequest;
import java.time.LocalDate;
import org.springframework.batch.item.ItemProcessor;

/**
 * PREMBAT 3200-CALCULATE-PREMIUM and the field moves of 3300-WRITE-PREMIUM-RECORD: rates one active
 * policy at the business date and builds its PREMIUMS row (coverage 1, the policy term, annual,
 * {@code CALC_BY = 'PREMBAT'}). A policy that cannot be rated becomes an error outcome and the run
 * continues with the next one (Q-25); PREMBAT's loop ended at the first failure.
 */
public class PremiumItemProcessor implements ItemProcessor<ActivePolicy, PremiumOutcome> {

  static final int COVERAGE_SEQ = 1;
  static final String CALC_BY = "PREMBAT";

  private final PremiumRater rater;
  private final LocalDate businessDate;

  public PremiumItemProcessor(PremiumRater rater, LocalDate businessDate) {
    this.rater = rater;
    this.businessDate = businessDate;
  }

  @Override
  public PremiumOutcome process(ActivePolicy policy) {
    try {
      return PremiumOutcome.rated(
          rater
              .rate(RatingRequest.of(policy.policyType(), businessDate))
              .toPremium(
                  policy.policyNumber(),
                  COVERAGE_SEQ,
                  policy.effectiveDate(),
                  policy.expiryDate(),
                  businessDate,
                  CALC_BY));
    } catch (RuntimeException e) {
      return PremiumOutcome.failed(policy.policyNumber(), e);
    }
  }
}
