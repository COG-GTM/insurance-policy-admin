package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One effective-dated row of {@code pas.rating_parameters}: the scalar rates PREMBAT hardcodes
 * (WS-TAX-RATE, the flat surcharge, the experience modification) plus the schedule mod and discount
 * it writes to PREMIUMS as literals.
 *
 * @param rateVersion identifier of this rate filing, carried into every breakdown for audit
 */
public record RatingParameters(
    String rateVersion,
    BigDecimal taxRate,
    BigDecimal surchargeAmount,
    BigDecimal experienceMod,
    BigDecimal scheduleMod,
    BigDecimal discountPct) {

  public RatingParameters {
    Objects.requireNonNull(rateVersion, "rateVersion");
    Objects.requireNonNull(taxRate, "taxRate");
    Objects.requireNonNull(surchargeAmount, "surchargeAmount");
    Objects.requireNonNull(experienceMod, "experienceMod");
    Objects.requireNonNull(scheduleMod, "scheduleMod");
    Objects.requireNonNull(discountPct, "discountPct");
  }
}
