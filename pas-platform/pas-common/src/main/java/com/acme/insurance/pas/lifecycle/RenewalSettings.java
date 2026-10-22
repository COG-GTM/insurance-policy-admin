package com.acme.insurance.pas.lifecycle;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * POLRNW configuration.
 *
 * @param upliftPct renewal uplift, legacy 5.00 (Q-06)
 * @param defaultCapPct rate-increase cap, legacy 15.00 (Q-04)
 * @param stateCapPct optional per-state cap overrides keyed by two-letter state code (Q-04)
 * @param maxOpenClaims renewal is blocked when open claims exceed this (Q-02)
 * @param daysBeforeExpiry first day of the renewal window relative to expiry; null disables (Q-17)
 * @param daysAfterExpiry last day of the renewal window relative to expiry; null disables (Q-17)
 */
public record RenewalSettings(
    BigDecimal upliftPct,
    BigDecimal defaultCapPct,
    Map<String, BigDecimal> stateCapPct,
    int maxOpenClaims,
    Integer daysBeforeExpiry,
    Integer daysAfterExpiry) {
  public static final BigDecimal LEGACY_UPLIFT_PCT = new BigDecimal("5.00");
  public static final BigDecimal LEGACY_CAP_PCT = new BigDecimal("15.00");
  public static final int DEFAULT_MAX_OPEN_CLAIMS = 3;

  public RenewalSettings {
    Objects.requireNonNull(upliftPct, "upliftPct is required");
    Objects.requireNonNull(defaultCapPct, "defaultCapPct is required");
    stateCapPct =
        stateCapPct == null
            ? Map.of()
            : stateCapPct.entrySet().stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().trim().toUpperCase(Locale.ROOT),
                        Map.Entry::getValue));
    if (maxOpenClaims < 0) {
      throw new IllegalArgumentException("maxOpenClaims must not be negative");
    }
  }

  /** Legacy behaviour with the Q-02 open-claims block and no renewal window. */
  public static RenewalSettings legacyDefaults() {
    return new RenewalSettings(
        LEGACY_UPLIFT_PCT, LEGACY_CAP_PCT, Map.of(), DEFAULT_MAX_OPEN_CLAIMS, null, null);
  }

  public BigDecimal capFor(String stateCode) {
    if (stateCode == null || stateCode.isBlank()) {
      return defaultCapPct;
    }
    return stateCapPct.getOrDefault(stateCode.trim().toUpperCase(Locale.ROOT), defaultCapPct);
  }

  public boolean windowEnabled() {
    return daysBeforeExpiry != null && daysAfterExpiry != null;
  }
}
