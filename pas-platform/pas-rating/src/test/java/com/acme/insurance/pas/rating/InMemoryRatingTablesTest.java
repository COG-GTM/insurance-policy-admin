package com.acme.insurance.pas.rating;

import static com.acme.insurance.pas.rating.RatingFixtures.EPOCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryRatingTablesTest {

  private static final LocalDate FILING = LocalDate.of(2025, 1, 1);

  @Test
  void filedFactorSetShipsEmptyAndCannotBeSelected() {
    assertThatThrownBy(() -> RatingFixtures.parity().load(FILING, RatingProperties.FILED))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("factor set FILED has no territory and/or class factors");
  }

  @Test
  void unknownFactorSetIsRejected() {
    assertThatThrownBy(() -> RatingFixtures.parity().load(FILING, "BOGUS"))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("unknown rating factor set BOGUS");
  }

  @Test
  void latestRowEffectiveOnTheRatingDateWins() {
    InMemoryRatingTables t =
        RatingFixtures.parity()
            .baseRate("AUT", FILING, new BigDecimal("900.00"))
            .parameters(FILING, RatingFixtures.params("2025-01", "0.0400", "30.00", "1.0000"));
    RatingSnapshot before = t.load(FILING.minusDays(1), RatingProperties.PARITY);
    RatingSnapshot on = t.load(FILING, RatingProperties.PARITY);
    assertThat(before.baseRate("AUT")).isEqualByComparingTo("850.00");
    assertThat(before.parameters().rateVersion()).isEqualTo("PREMBAT-3200");
    assertThat(on.baseRate("AUT")).isEqualByComparingTo("900.00");
    assertThat(on.baseRate("HOM")).isEqualByComparingTo("1200.00");
    assertThat(on.parameters().rateVersion()).isEqualTo("2025-01");
  }

  @Test
  void nothingEffectiveYetIsADataError() {
    InMemoryRatingTables t =
        new InMemoryRatingTables()
            .factorSet("PARITY")
            .baseRate("*", FILING, BigDecimal.TEN)
            .parameters(FILING, RatingFixtures.prembatParameters())
            .territoryFactor("PARITY", "*", EPOCH, BigDecimal.ONE)
            .classFactor("PARITY", "*", EPOCH, BigDecimal.ONE);
    assertThatThrownBy(() -> t.load(FILING.minusDays(1), "PARITY"))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("no rating parameters effective");
  }

  @Test
  void snapshotRequiresOtherBranchBaseRate() {
    assertThatThrownBy(
            () ->
                new RatingSnapshot(
                    FILING,
                    "PARITY",
                    Map.of("AUT", BigDecimal.TEN),
                    Map.of("*", BigDecimal.ONE),
                    Map.of("*", BigDecimal.ONE),
                    RatingFixtures.prembatParameters()))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("WHEN OTHER");
  }

  @Test
  void paddedCharCodesFromTheDatabaseMatch() {
    RatingSnapshot s =
        new RatingSnapshot(
            FILING,
            "FILED",
            Map.of("*  ", BigDecimal.TEN, "AUT", BigDecimal.ONE),
            Map.of("NY01  ", new BigDecimal("1.5000"), "*     ", BigDecimal.ONE),
            Map.of("*    ", BigDecimal.ONE),
            RatingFixtures.prembatParameters());
    assertThat(s.baseRate("XYZ")).isEqualTo(BigDecimal.TEN);
    assertThat(s.territoryFactor("NY01")).isEqualByComparingTo("1.5");
    assertThat(s.territoryFactor("NY01  ")).isEqualByComparingTo("1.5");
    assertThat(s.territoryFactor("  NY01")).isEqualByComparingTo("1");
    assertThat(s.classFactor(null)).isEqualByComparingTo("1");
  }
}
