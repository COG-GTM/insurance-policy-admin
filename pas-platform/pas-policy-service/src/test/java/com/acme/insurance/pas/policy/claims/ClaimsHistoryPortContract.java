package com.acme.insurance.pas.policy.claims;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.claims.InMemoryClaimsHistoryAdapter.Claim;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract every {@link ClaimsHistoryPort} adapter must satisfy (claims-interface-assumptions.md).
 * The real Claims adapter (Pending: Claims team) subclasses this with a fixture-loaded source.
 */
abstract class ClaimsHistoryPortContract {

  static final LocalDate AS_OF = LocalDate.of(2025, 6, 1);

  /** An adapter whose source holds exactly {@code claims}. */
  protected abstract ClaimsHistoryPort adapterWith(List<Claim> claims);

  @Test
  void unknownPolicyHasNoClaims() {
    ClaimsSummary s = adapterWith(List.of()).summarize("POL001000000", AS_OF);
    assertThat(s.openClaimCount()).isZero();
    assertThat(s.claimCount5y()).isZero();
    assertThat(s.incurredAmount5y()).isEqualByComparingTo("0.00").hasToString("0.00");
  }

  @Test
  void fiveYearWindowIncludesTheBoundaryDayAndHasNoUpperBound() {
    ClaimsSummary s =
        adapterWith(
                List.of(
                    claim(LocalDate.of(2020, 6, 1), "CLOSED", "100.00"),
                    claim(LocalDate.of(2020, 5, 31), "CLOSED", "999.00"),
                    claim(LocalDate.of(2025, 6, 2), "CLOSED", "50.25")))
            .summarize("POL001000000", AS_OF);
    assertThat(s.claimCount5y()).isEqualTo(2);
    assertThat(s.incurredAmount5y()).isEqualByComparingTo("150.25");
  }

  @Test
  void leapDayAsOfUsesCalendarYears() {
    ClaimsSummary s =
        adapterWith(List.of(claim(LocalDate.of(2019, 2, 28), "CLOSED", "10.00")))
            .summarize("POL001000000", LocalDate.of(2024, 2, 29));
    assertThat(s.claimCount5y()).isEqualTo(1);
  }

  @Test
  void openClaimsCountRegardlessOfDateAndOnlyForThePolicy() {
    ClaimsSummary s =
        adapterWith(
                List.of(
                    claim(LocalDate.of(2010, 1, 1), "OPEN", "0.00"),
                    claim(LocalDate.of(2025, 1, 1), "OPEN", "10.00"),
                    new Claim("POL009999999", LocalDate.of(2025, 1, 1), "OPEN", BigDecimal.TEN)))
            .summarize("POL001000000", AS_OF);
    assertThat(s.openClaimCount()).isEqualTo(2);
    assertThat(s.claimCount5y()).isEqualTo(1);
  }

  @Test
  void recoveriesCanMakeIncurredNegative() {
    ClaimsSummary s =
        adapterWith(List.of(claim(LocalDate.of(2024, 1, 1), "CLOSED", "-500.00")))
            .summarize("POL001000000", AS_OF);
    assertThat(s.incurredAmount5y()).isEqualByComparingTo("-500.00");
  }

  private static Claim claim(LocalDate date, String status, String incurred) {
    return new Claim("POL001000000", date, status, new BigDecimal(incurred));
  }
}
