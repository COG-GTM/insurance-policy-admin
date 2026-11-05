package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.RatingParameter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PremiumRaterTest {

  static final LocalDate RUN = LocalDate.of(2025, 3, 1);

  final List<LocalDate> loads = new ArrayList<>();
  final ReferenceRatingTables tables =
      new ReferenceRatingTables(
          RatingReferenceData.of(
              date -> {
                loads.add(date);
                return RatingTableTest.baseRates();
              },
              date -> RatingTableTest.parameters()));

  /** Territory T1 = 1.1000 and class C1 = 0.9000 from 2025-01-01. */
  static final FactorLookup TERRITORY =
      (code, date) ->
          "T1".equals(code) && !date.isBefore(LocalDate.of(2025, 1, 1))
              ? Optional.of(new BigDecimal("1.1000"))
              : Optional.empty();

  static final FactorLookup CLASS =
      (code, date) -> "C1".equals(code) ? Optional.of(new BigDecimal("0.9000")) : Optional.empty();

  @Test
  void legacyNeutralIgnoresTheFactorTables() {
    PremiumRater rater =
        new PremiumRater(tables, RatingFactorMode.LEGACY_NEUTRAL.source(TERRITORY, CLASS));

    PremiumBreakdown premium = rater.rate(new RatingRequest("AUT", RUN, "T1", "C1"));

    assertThat(premium.factors()).isEqualTo(RatingFactors.NEUTRAL);
    // 700.00, tax 28.00, surcharge 10.00
    assertThat(premium.totalPremium()).hasToString("738.00");
  }

  @Test
  void tableDrivenAppliesTerritoryAndClassFactors() {
    PremiumRater rater =
        new PremiumRater(tables, RatingFactorMode.TABLE_DRIVEN.source(TERRITORY, CLASS));

    PremiumBreakdown premium = rater.rate(new RatingRequest("AUT", RUN, "T1", "C1"));

    assertThat(premium.factors().territoryFactor()).hasToString("1.1000");
    assertThat(premium.factors().classFactor()).hasToString("0.9000");
    assertThat(premium.factors().experienceMod()).hasToString("1.0000");
    assertThat(premium.territoryPremium()).hasToString("770.00");
    assertThat(premium.classPremium()).hasToString("693.00");
    assertThat(premium.tax()).hasToString("27.72");
    assertThat(premium.totalPremium()).hasToString("730.72");
  }

  @Test
  void tableDrivenKeepsNeutralWhenNoRowOrNoCodeApplies() {
    PremiumRater rater =
        new PremiumRater(tables, RatingFactorMode.TABLE_DRIVEN.source(TERRITORY, CLASS));

    for (RatingRequest request :
        List.of(
            RatingRequest.of("AUT", RUN),
            new RatingRequest("AUT", RUN, "  ", ""),
            new RatingRequest("AUT", RUN, "T2", "C2"),
            new RatingRequest("AUT", LocalDate.of(2024, 12, 31), "T1", null))) {
      assertThat(rater.rate(request).factors())
          .as(request.toString())
          .isEqualTo(RatingFactors.NEUTRAL);
    }
  }

  @Test
  void ratesAreLoadedOncePerRatingDate() {
    PremiumRater rater = new PremiumRater(tables, RatingFactorSource.LEGACY_NEUTRAL);

    rater.rate(RatingRequest.of("AUT", RUN));
    rater.rate(RatingRequest.of("HOM", RUN));
    rater.rate(RatingRequest.of("AUT", RUN.plusDays(1)));
    assertThat(loads).containsExactly(RUN, RUN.plusDays(1));

    tables.evict();
    rater.rate(RatingRequest.of("AUT", RUN));
    assertThat(loads).hasSize(3);
  }

  @Test
  void impactReportListsBeforeAndAfterPerPolicy() {
    PremiumRater legacy =
        new PremiumRater(tables, RatingFactorMode.LEGACY_NEUTRAL.source(TERRITORY, CLASS));
    PremiumRater tableDriven =
        new PremiumRater(tables, RatingFactorMode.TABLE_DRIVEN.source(TERRITORY, CLASS));
    Map<String, RatingRequest> book = new LinkedHashMap<>();
    book.put("POL-00000001", new RatingRequest("AUT", RUN, "T1", "C1"));
    book.put("POL-00000002", new RatingRequest("HOM", RUN, "T9", null));

    PremiumImpactReport report = PremiumImpactReport.compare(book, legacy, tableDriven);

    assertThat(report.changedCount()).isEqualTo(1);
    assertThat(report.totalDelta()).hasToString("-7.28");
    assertThat(report.toCsv())
        .isEqualTo(
            "policy_number,legacy_total,table_driven_total,delta,territory_factor,class_factor\n"
                + "POL-00000001,738.00,730.72,-7.28,1.1000,0.9000\n"
                + "POL-00000002,356.66,356.66,0.00,1.0000,1.0000\n");
  }

  @Test
  void baseRateRowsAreMatchedExactly() {
    // Q-35: lower case or padded codes are "other", as PREMBAT's EVALUATE.
    PremiumRater rater = new PremiumRater(tables, RatingFactorSource.LEGACY_NEUTRAL);
    assertThat(rater.rate(RatingRequest.of("aut", RUN)).basePremium()).hasToString("900.00");
    assertThat(new BaseRate("AUT", RUN, BigDecimal.ONE).policyType()).isEqualTo("AUT");
    assertThat(RatingTableTest.parameters()).extracting(RatingParameter::code).hasSize(3);
  }
}
