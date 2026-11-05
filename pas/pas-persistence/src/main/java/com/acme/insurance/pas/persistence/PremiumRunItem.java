package com.acme.insurance.pas.persistence;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Outcome of one policy in one premium recalculation run: {@code UPDATED} with the premium written,
 * or {@code ERROR} with an error class and a short detail (never policy-holder data).
 */
public record PremiumRunItem(
    String policyNumber, Status status, BigDecimal totalPremium, String errorCode, String detail) {

  public enum Status {
    UPDATED("U"),
    ERROR("E");

    private final String code;

    Status(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    static Status of(String code) {
      return "U".equals(code) ? UPDATED : ERROR;
    }
  }

  public PremiumRunItem {
    Objects.requireNonNull(policyNumber, "policyNumber");
    Objects.requireNonNull(status, "status");
    if (status == Status.UPDATED) {
      Objects.requireNonNull(totalPremium, "totalPremium");
    }
    errorCode = truncate(errorCode, 64);
    detail = truncate(detail, 256);
  }

  public static PremiumRunItem updated(String policyNumber, BigDecimal totalPremium) {
    return new PremiumRunItem(policyNumber, Status.UPDATED, totalPremium, null, null);
  }

  public static PremiumRunItem error(String policyNumber, String errorCode, String detail) {
    return new PremiumRunItem(policyNumber, Status.ERROR, null, errorCode, detail);
  }

  private static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
