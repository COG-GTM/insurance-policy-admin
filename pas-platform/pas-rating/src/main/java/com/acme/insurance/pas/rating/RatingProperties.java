package com.acme.insurance.pas.rating;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pas.rating.*} settings.
 *
 * @param factorSet territory/class factor set ({@code PAS_RATING_FACTOR_SET}). {@code PARITY}
 *     (default) reproduces PREMBAT: every factor 1.0000 (quirk Q-03). {@code FILED} switches to the
 *     real filed factors — a business decision that stays off until signed off (OQ-04).
 */
@ConfigurationProperties("pas.rating")
public record RatingProperties(@DefaultValue(RatingProperties.PARITY) String factorSet) {

  public static final String PARITY = "PARITY";
  public static final String FILED = "FILED";

  public RatingProperties {
    if (factorSet == null || factorSet.isBlank()) {
      throw new IllegalArgumentException("pas.rating.factor-set must not be blank");
    }
  }
}
