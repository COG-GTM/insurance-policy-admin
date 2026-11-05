package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Renewal pricing parameters (POLRNW 4000-CALCULATE-NEW-PREMIUM). The legacy program hardcodes a 5%
 * uplift (Q-05) and a flat 15% rate-increase cap (Q-04); both are configuration here so
 * state-specific caps can follow. Percentages fit COBOL {@code S9(3)V99}. The legacy values are the
 * defaults of {@code pas.policy-change.*}, so no rate is hardcoded here (AB-522).
 *
 * @param upliftPct renewal uplift in percent
 * @param rateIncreaseCapPct largest rate increase in percent; a larger uplift is capped to it
 * @param leapDayExpiry how a term that expires on 29 February is renewed (Q-01, FU-01)
 */
public record RenewalRules(
    BigDecimal upliftPct, BigDecimal rateIncreaseCapPct, LeapDayExpiry leapDayExpiry) {

  public RenewalRules {
    upliftPct = percent(upliftPct, "upliftPct");
    rateIncreaseCapPct = percent(rateIncreaseCapPct, "rateIncreaseCapPct");
    Objects.requireNonNull(leapDayExpiry, "leapDayExpiry");
  }

  private static BigDecimal percent(BigDecimal value, String field) {
    Objects.requireNonNull(value, field);
    if (value.signum() < 0 || value.scale() > 2 || value.precision() - value.scale() > 3) {
      throw new IllegalArgumentException(
          field + " must be a COBOL S9(3)V99 percentage, 0 or more: " + value);
    }
    return value.setScale(2);
  }

  /** The renewal expiry of a term that ends on 29 February (Q-01, open question FU-01). */
  public enum LeapDayExpiry {
    /** 28 February of the following year: the term keeps its month (recommended). */
    FEB_28,
    /** 1 March of the following year: the term keeps its length in days from 1 March. */
    MAR_01
  }
}
