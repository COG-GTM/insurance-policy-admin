package com.acme.insurance.pas.parity.adapters;

import com.acme.insurance.pas.parity.Effect;
import com.acme.insurance.pas.parity.FixtureCase;
import com.acme.insurance.pas.parity.ProgramAdapter;
import com.acme.insurance.pas.parity.RunResult;
import com.acme.insurance.pas.rating.PremiumBreakdown;
import com.acme.insurance.pas.rating.PremiumRater;
import com.acme.insurance.pas.rating.RatingFactorMode;
import com.acme.insurance.pas.rating.RatingFactors;
import com.acme.insurance.pas.rating.RatingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Suite {@code prembat-rating}: rates each fixture policy row with the shared {@link PremiumRater}
 * over the Flyway-migrated reference tables ({@link ReferenceDatabase}) and reports the PREMIUMS
 * row PREMBAT inserts for it ({@code 3300-WRITE-PREMIUM-RECORD}). No rate is held here: the golden
 * files prove the loaded reference rows reproduce PREMBAT's hardcoded values (AB-522).
 */
public final class PrembatRatingAdapter implements ProgramAdapter {

  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

  private final PremiumRater rater;

  /** The production default: factor source {@code legacy-neutral}. */
  public PrembatRatingAdapter() {
    this(ReferenceDatabase.rater(RatingFactorMode.LEGACY_NEUTRAL));
  }

  PrembatRatingAdapter(PremiumRater rater) {
    this.rater = Objects.requireNonNull(rater, "rater");
  }

  @Override
  public RunResult run(FixtureCase fixture) {
    List<Effect> effects = new ArrayList<>();
    for (JsonNode row : fixture.input().path("ROWS")) {
      String policyType = Cobol.alphanumeric(fixture.text(row, "POLICY_TYPE", ""), 3);
      PremiumBreakdown premium = rater.rate(RatingRequest.of(policyType, fixture.runDate()));
      RatingFactors factors = premium.factors();
      effects.add(
          Effect.builder("SQL INSERT PREMIUMS")
              .sqlcode(0)
              .text("POLICY_NUMBER", Cobol.alphanumeric(fixture.text(row, "POLICY_NUMBER", ""), 12))
              .number("COVERAGE_SEQ", 1)
              .text("TERM_EFFECTIVE_DATE", fixture.text(row, "EFFECTIVE_DATE", "0"))
              .text("TERM_EXPIRY_DATE", fixture.text(row, "EXPIRY_DATE", "0"))
              .number("BASE_RATE", premium.basePremium().setScale(4, RoundingMode.UNNECESSARY))
              .number("TERRITORY_FACTOR", factors.territoryFactor())
              .number("CLASS_FACTOR", factors.classFactor())
              .number("EXPERIENCE_MOD", factors.experienceMod())
              .number("SCHEDULE_MOD", factors.scheduleMod())
              .number("DISCOUNT_PCT", factors.discountPct())
              .number("SURCHARGE_AMT", premium.surcharge())
              .number("TAX_AMT", premium.tax())
              .number("TOTAL_PREMIUM", premium.totalPremium())
              .text("INSTALLMENT_CODE", "AN")
              .text("CALC_DATE", fixture.runDate().format(YYYYMMDD))
              .text("CALC_BY", "PREMBAT")
              .probe("WS-TERR-PREMIUM", premium.territoryPremium())
              .probe("WS-CLASS-PREMIUM", premium.classPremium())
              .probe("WS-MOD-PREMIUM", premium.modifiedPremium())
              .build());
    }
    return RunResult.completed(0, effects);
  }
}
