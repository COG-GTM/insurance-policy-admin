package com.acme.insurance.pas.policy.claims;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stub adapter ({@code pas.claims.adapter=stub}) for local development and test environments:
 * claims are held in memory and start empty. It implements the full {@link ClaimsHistoryPort}
 * contract so it doubles as the reference implementation for the contract test.
 */
public class InMemoryClaimsHistoryAdapter implements ClaimsHistoryPort {

  /** Claim status Claims uses for an open claim (assumed; Pending: Claims team). */
  public static final String OPEN = "OPEN";

  private static final int HISTORY_YEARS = 5;

  /** One claim row as the Claims interface would expose it. */
  public record Claim(
      String policyNumber, LocalDate claimDate, String status, BigDecimal incurredAmount) {

    public Claim {
      Objects.requireNonNull(policyNumber, "policyNumber");
      Objects.requireNonNull(claimDate, "claimDate");
      Objects.requireNonNull(incurredAmount, "incurredAmount");
    }
  }

  private final List<Claim> claims = new CopyOnWriteArrayList<>();

  public void add(Claim claim) {
    claims.add(Objects.requireNonNull(claim, "claim"));
  }

  public void clear() {
    claims.clear();
  }

  @Override
  public ClaimsSummary summarize(String policyNumber, LocalDate asOf) {
    Objects.requireNonNull(asOf, "asOf");
    String key = normalise(policyNumber);
    LocalDate from = asOf.minusYears(HISTORY_YEARS);
    long open = 0;
    long count = 0;
    BigDecimal incurred = BigDecimal.ZERO;
    for (Claim c : claims) {
      if (!normalise(c.policyNumber()).equals(key)) {
        continue;
      }
      if (c.status() != null && OPEN.equals(c.status().strip().toUpperCase(Locale.ROOT))) {
        open++;
      }
      if (!c.claimDate().isBefore(from)) {
        count++;
        incurred = incurred.add(c.incurredAmount());
      }
    }
    return new ClaimsSummary(open, count, incurred);
  }

  private static String normalise(String policyNumber) {
    return policyNumber == null ? "" : policyNumber.strip();
  }
}
