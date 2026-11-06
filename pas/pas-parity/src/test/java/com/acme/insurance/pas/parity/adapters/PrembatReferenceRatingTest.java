package com.acme.insurance.pas.parity.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.TerritoryFactor;
import com.acme.insurance.pas.parity.Effect;
import com.acme.insurance.pas.parity.FixtureCase;
import com.acme.insurance.pas.parity.ParityData;
import com.acme.insurance.pas.persistence.RatingFactorJdbcRepository;
import com.acme.insurance.pas.rating.PremiumBreakdown;
import com.acme.insurance.pas.rating.PremiumImpactReport;
import com.acme.insurance.pas.rating.PremiumRater;
import com.acme.insurance.pas.rating.RatingFactorMode;
import com.acme.insurance.pas.rating.RatingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AB-522 acceptance beyond {@code ParityTest}: every PREMIUMS row in every PREMBAT golden file
 * (both PREMBAT suites, including the batch-control suite whose adapter is AB-523's) is reproduced
 * to the cent by rating from the reference tables, and the table-driven flag is parity-neutral
 * until factor rows are loaded.
 */
class PrembatReferenceRatingTest {

  private static final ParityData DATA = ParityData.fromSystemProperty();
  private static final String[] MONEY_FIELDS = {
    "BASE_RATE",
    "TERRITORY_FACTOR",
    "CLASS_FACTOR",
    "EXPERIENCE_MOD",
    "SCHEDULE_MOD",
    "DISCOUNT_PCT",
    "SURCHARGE_AMT",
    "TAX_AMT",
    "TOTAL_PREMIUM"
  };

  @Test
  void everyPrembatGoldenPremiumRowIsReproducedFromTheReferenceTables() {
    PremiumRater rater = ReferenceDatabase.rater(RatingFactorMode.LEGACY_NEUTRAL);
    int rows = 0;
    for (JsonNode suite : DATA.suites()) {
      if (!"PREMBAT".equals(suite.get("program").asText())) {
        continue;
      }
      String suiteName = suite.get("suite").asText();
      for (FixtureCase fixture : DATA.fixtures(suite)) {
        Map<String, String> typeByPolicy = new HashMap<>();
        for (JsonNode row : fixture.input().path("ROWS")) {
          typeByPolicy.put(
              fixture.text(row, "POLICY_NUMBER", "").stripTrailing(),
              Cobol.alphanumeric(fixture.text(row, "POLICY_TYPE", ""), 3));
        }
        for (Effect golden : DATA.golden(suiteName, fixture.id()).effects()) {
          if (!golden.op().equals("SQL INSERT PREMIUMS")) {
            continue;
          }
          String where = suiteName + "/" + fixture.id() + " " + golden.text().get("POLICY_NUMBER");
          String type = typeByPolicy.get(golden.text().get("POLICY_NUMBER"));
          assertThat(type).as(where).isNotNull();
          Map<String, BigDecimal> java =
              values(rater.rate(RatingRequest.of(type, fixture.runDate())));
          for (String field : MONEY_FIELDS) {
            assertThat(java.get(field))
                .as(where + " " + field)
                .isEqualByComparingTo(golden.numbers().get(field));
          }
          golden
              .probes()
              .forEach(
                  (probe, value) ->
                      assertThat(java.get(probe))
                          .as(where + " " + probe)
                          .isEqualByComparingTo(value));
          rows++;
        }
      }
    }
    assertThat(rows).as("PREMIUMS rows checked").isGreaterThanOrEqualTo(19);
  }

  @Test
  void tableDrivenFactorsWithoutFactorRowsKeepParity() {
    PrembatRatingAdapter legacy = new PrembatRatingAdapter();
    PrembatRatingAdapter tableDriven =
        new PrembatRatingAdapter(ReferenceDatabase.rater(RatingFactorMode.TABLE_DRIVEN));
    for (JsonNode suite : DATA.suites()) {
      if (suite.get("suite").asText().equals("prembat-rating")) {
        for (FixtureCase fixture : DATA.fixtures(suite)) {
          assertThat(tableDriven.run(fixture)).as(fixture.id()).isEqualTo(legacy.run(fixture));
        }
      }
    }
  }

  @Test
  void tableDrivenAppliesLoadedFactorsOnlyFromTheirEffectiveDate() {
    new RatingFactorJdbcRepository(ReferenceDatabase.jdbc())
        .insert(new TerritoryFactor("ZZ522", LocalDate.of(2025, 1, 1), new BigDecimal("1.1000")));
    PremiumRater legacy = ReferenceDatabase.rater(RatingFactorMode.LEGACY_NEUTRAL);
    PremiumRater tableDriven = ReferenceDatabase.rater(RatingFactorMode.TABLE_DRIVEN);
    Map<String, RatingRequest> book = new LinkedHashMap<>();
    book.put("POL-00000001", new RatingRequest("AUT", LocalDate.of(2025, 3, 1), "ZZ522", null));
    book.put("POL-00000002", new RatingRequest("AUT", LocalDate.of(2024, 12, 31), "ZZ522", null));

    PremiumImpactReport report = PremiumImpactReport.compare(book, legacy, tableDriven);

    // 850.00 * 1.1000 = 935.00; tax 32.725 -> 32.72; total 992.72 vs legacy 904.75.
    assertThat(report.toCsv())
        .isEqualTo(
            "policy_number,legacy_total,table_driven_total,delta,territory_factor,class_factor\n"
                + "POL-00000001,904.75,992.72,87.97,1.1000,1.0000\n"
                + "POL-00000002,904.75,904.75,0.00,1.0000,1.0000\n");
  }

  private static Map<String, BigDecimal> values(PremiumBreakdown premium) {
    Map<String, BigDecimal> values = new HashMap<>();
    values.put("BASE_RATE", premium.basePremium());
    values.put("TERRITORY_FACTOR", premium.factors().territoryFactor());
    values.put("CLASS_FACTOR", premium.factors().classFactor());
    values.put("EXPERIENCE_MOD", premium.factors().experienceMod());
    values.put("SCHEDULE_MOD", premium.factors().scheduleMod());
    values.put("DISCOUNT_PCT", premium.factors().discountPct());
    values.put("SURCHARGE_AMT", premium.surcharge());
    values.put("TAX_AMT", premium.tax());
    values.put("TOTAL_PREMIUM", premium.totalPremium());
    values.put("WS-TERR-PREMIUM", premium.territoryPremium());
    values.put("WS-CLASS-PREMIUM", premium.classPremium());
    values.put("WS-MOD-PREMIUM", premium.modifiedPremium());
    return values;
  }
}
