package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.domain.Premium;
import com.acme.insurance.pas.persistence.PremiumRunItem;
import java.util.Objects;

/** Result of rating one active policy: the PREMIUMS row to write, or why it could not be rated. */
public record PremiumOutcome(
    String policyNumber, Premium premium, String errorCode, String detail) {

  public PremiumOutcome {
    Objects.requireNonNull(policyNumber, "policyNumber");
    if ((premium == null) == (errorCode == null)) {
      throw new IllegalArgumentException("exactly one of premium and errorCode is required");
    }
  }

  public static PremiumOutcome rated(Premium premium) {
    return new PremiumOutcome(premium.policyNumber(), premium, null, null);
  }

  public static PremiumOutcome failed(String policyNumber, Throwable cause) {
    return new PremiumOutcome(
        policyNumber, null, cause.getClass().getSimpleName(), String.valueOf(cause.getMessage()));
  }

  public boolean rated() {
    return premium != null;
  }

  PremiumRunItem runItem() {
    return rated()
        ? PremiumRunItem.updated(policyNumber, premium.totalPremium())
        : PremiumRunItem.error(policyNumber, errorCode, detail);
  }
}
