package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.RatingParameter;
import com.acme.insurance.pas.domain.RatingRateHistory;
import com.acme.insurance.pas.domain.TerritoryFactor;
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
      ReferenceRatingTables.snapshotOf(
          RatingReferenceData.of(
              date -> {
                loads.add(date);
                return RatingTableTest.baseRates();
              },
              date -> RatingTableTest.parameters(),
              () ->
                  new RatingRateHistory(
                      RatingTableTest.baseRates(), RatingTableTest.parameters())));

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

  @Test
  void cachedTablesAreReloadedAfterTheirMaxAge() {
    java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
        new java.util.concurrent.atomic.AtomicReference<>(
            java.time.Instant.parse("2025-06-01T00:00:00Z"));
    java.time.Clock clock =
        new java.time.Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
          }

          @Override
          public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public java.time.Instant instant() {
            return now.get();
          }
        };
    List<BaseRate> current = new ArrayList<>(RatingTableTest.baseRates());
    ReferenceRatingTables fresh =
        new ReferenceRatingTables(
            RatingReferenceData.of(
                date -> List.copyOf(current),
                date -> RatingTableTest.parameters(),
                () -> new RatingRateHistory(current, RatingTableTest.parameters())),
            java.time.Duration.ofMinutes(5),
            clock);
    ReferenceRatingTables batchRun = fresh.snapshot();
    LocalDate july = LocalDate.of(2025, 7, 1);
    assertThat(fresh.tableFor(july).baseRate("AUT")).hasToString("700.00");
    assertThat(batchRun.tableFor(july).baseRate("AUT")).hasToString("700.00");

    current.set(0, new BaseRate("AUT", july, new BigDecimal("900.00")));
    now.set(now.get().plusSeconds(300));
    assertThat(fresh.tableFor(july).baseRate("AUT")).as("within max age").hasToString("700.00");
    now.set(now.get().plusSeconds(1));
    assertThat(fresh.tableFor(july).baseRate("AUT")).as("after max age").hasToString("900.00");
    assertThat(batchRun.tableFor(july).baseRate("AUT")).as("snapshot").hasToString("700.00");
  }

  @Test
  void aSnapshotResolvesEveryDateFromTheRowsCopiedAtItsStart() {
    LocalDate july = LocalDate.of(2025, 7, 1);
    LocalDate august = LocalDate.of(2025, 8, 1);
    List<BaseRate> live = new ArrayList<>(RatingTableTest.baseRates());
    ReferenceRatingTables tables =
        ReferenceRatingTables.snapshotOf(
            RatingReferenceData.history(live, RatingTableTest.parameters()));
    RatingReferenceData data =
        RatingReferenceData.of(
            date ->
                RatingReferenceData.history(live, RatingTableTest.parameters())
                    .baseRatesInForce(date),
            date -> RatingTableTest.parameters(),
            () -> new RatingRateHistory(live, RatingTableTest.parameters()));
    ReferenceRatingTables batchRun =
        new ReferenceRatingTables(data, java.time.Duration.ZERO, java.time.Clock.systemUTC())
            .snapshot();

    assertThat(batchRun.tableFor(july).baseRate("AUT")).hasToString("700.00");
    live.add(new BaseRate("AUT", july, new BigDecimal("900.00")));
    // August is first requested after the insert and still sees the rates of the run's start.
    assertThat(batchRun.tableFor(august).baseRate("AUT")).hasToString("700.00");
    assertThat(tables.tableFor(august).baseRate("AUT")).hasToString("700.00");
    assertThat(data.baseRatesInForce(august))
        .filteredOn(r -> r.policyType().equals("AUT"))
        .extracting(r -> r.baseRate().toPlainString())
        .containsExactly("900.00");
    assertThat(
            RatingReferenceData.history(live, RatingTableTest.parameters())
                .baseRatesInForce(july.minusDays(1)))
        .filteredOn(r -> r.policyType().equals("AUT"))
        .extracting(r -> r.baseRate().toPlainString())
        .containsExactly("700.00");
  }

  @Test
  void aRaterSnapshotPinsRatesAndFactorsCopiedAtItsStart() {
    LocalDate july = LocalDate.of(2025, 7, 1);
    List<BaseRate> liveRates = new ArrayList<>(RatingTableTest.baseRates());
    List<TerritoryFactor> liveTerritories =
        new ArrayList<>(
            List.of(
                new TerritoryFactor("T1    ", LocalDate.of(2025, 1, 1), new BigDecimal("1.1000"))));
    RatingReferenceData data =
        RatingReferenceData.of(
            date ->
                RatingReferenceData.history(liveRates, RatingTableTest.parameters())
                    .baseRatesInForce(date),
            date -> RatingTableTest.parameters(),
            () ->
                new RatingRateHistory(
                    liveRates, RatingTableTest.parameters(), liveTerritories, List.of()));
    FactorLookup liveTerritory =
        (code, date) ->
            FactorLookup.of(
                    liveTerritories,
                    TerritoryFactor::territoryCode,
                    TerritoryFactor::effectiveDate,
                    TerritoryFactor::ratingFactor)
                .factor(code, date);
    PremiumRater live =
        new PremiumRater(
            new ReferenceRatingTables(data, java.time.Duration.ZERO, java.time.Clock.systemUTC()),
            RatingFactorMode.TABLE_DRIVEN.source(liveTerritory, (code, date) -> Optional.empty()));
    RatingRequest request = new RatingRequest("AUT", july, "T1", null);

    PremiumRater batchRun = live.snapshot();
    assertThat(batchRun.rate(request).territoryPremium()).hasToString("770.00");

    liveRates.add(new BaseRate("AUT", july, new BigDecimal("800.00")));
    liveTerritories.add(new TerritoryFactor("T1", july, new BigDecimal("1.5000")));

    // 800.00 * 1.5000 live; the run keeps 700.00 * 1.1000, also for a date first rated now.
    assertThat(live.rate(request).territoryPremium()).hasToString("1200.00");
    assertThat(batchRun.rate(request).territoryPremium()).hasToString("770.00");
    assertThat(batchRun.rate(new RatingRequest("AUT", july.plusMonths(1), "T1", null)).factors())
        .extracting(RatingFactors::territoryFactor)
        .hasToString("1.1000");
  }

  @Test
  void aLegacyNeutralSnapshotStaysNeutral() {
    PremiumRater batchRun =
        new PremiumRater(tables, RatingFactorMode.LEGACY_NEUTRAL.source(TERRITORY, CLASS))
            .snapshot();
    assertThat(batchRun.rate(new RatingRequest("AUT", RUN, "T1", "C1")).totalPremium())
        .hasToString("738.00");
  }

  @Test
  void pinnedFactorLookupsTakeTheLatestRowAndIgnoreTrailingBlanks() {
    FactorLookup lookup =
        FactorLookup.of(
            List.of(
                new TerritoryFactor("T1    ", LocalDate.of(2025, 1, 1), new BigDecimal("1.1000")),
                new TerritoryFactor("T1    ", LocalDate.of(2025, 6, 1), new BigDecimal("1.2000"))),
            TerritoryFactor::territoryCode,
            TerritoryFactor::effectiveDate,
            TerritoryFactor::ratingFactor);
    assertThat(lookup.factor("T1", LocalDate.of(2024, 12, 31))).isEmpty();
    assertThat(lookup.factor("T1", LocalDate.of(2025, 5, 31)))
        .hasValueSatisfying(f -> assertThat(f).hasToString("1.1000"));
    assertThat(lookup.factor("T1    ", LocalDate.of(2025, 6, 1)))
        .hasValueSatisfying(f -> assertThat(f).hasToString("1.2000"));
    assertThat(lookup.factor(" T1", LocalDate.of(2025, 6, 1))).isEmpty();
  }

  @Test
  void expiredDatesAreDroppedFromTheCacheOnTheNextLoad() {
    java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
        new java.util.concurrent.atomic.AtomicReference<>(
            java.time.Instant.parse("2025-06-01T00:00:00Z"));
    java.time.Clock clock =
        new java.time.Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
          }

          @Override
          public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public java.time.Instant instant() {
            return now.get();
          }
        };
    ReferenceRatingTables cache =
        new ReferenceRatingTables(
            RatingReferenceData.history(RatingTableTest.baseRates(), RatingTableTest.parameters()),
            java.time.Duration.ofMinutes(5),
            clock);
    for (int day = 0; day < 10; day++) {
      cache.tableFor(RUN.plusDays(day));
    }
    assertThat(cache.cachedDates()).isEqualTo(10);

    now.set(now.get().plusSeconds(301));
    cache.tableFor(RUN.plusDays(100));
    assertThat(cache.cachedDates()).isEqualTo(1);
  }
}
