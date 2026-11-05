package com.acme.insurance.pas.policyservice.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.TerritoryFactor;
import com.acme.insurance.pas.persistence.RatingFactorJdbcRepository;
import com.acme.insurance.pas.policyservice.TestJwt;
import com.acme.insurance.pas.policyservice.TestcontainersConfiguration;
import com.acme.insurance.pas.rating.PremiumRater;
import com.acme.insurance.pas.rating.RatingRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The policy service's PremiumRater over the migrated schema, flag off and on (AB-522). */
class RatingConfigurationIT {

  static final LocalDate RUN = LocalDate.of(2025, 3, 1);
  static final RatingRequest AUT_IN_T1 = new RatingRequest("AUT", RUN, "T1", null);

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  static void loadTerritoryFactor(RatingFactorJdbcRepository factors) {
    factors.insert(new TerritoryFactor("T1", LocalDate.of(2025, 1, 1), new BigDecimal("1.2000")));
  }

  @Nested
  @SpringBootTest
  @Import(TestcontainersConfiguration.class)
  class DefaultFlag {
    @Autowired PremiumRater rater;
    @Autowired RatingFactorJdbcRepository factors;

    @Test
    void ratesFromTheReferenceTablesWithNeutralFactors() {
      loadTerritoryFactor(factors);
      assertThat(rater.rate(AUT_IN_T1).totalPremium()).hasToString("904.75");
      assertThat(rater.snapshot().rate(AUT_IN_T1).totalPremium()).hasToString("904.75");
    }
  }

  @Nested
  @SpringBootTest(properties = "pas.rating.factor-source=table-driven")
  @Import(TestcontainersConfiguration.class)
  class TableDrivenFlag {
    @Autowired PremiumRater rater;
    @Autowired RatingFactorJdbcRepository factors;

    @Test
    void appliesTheLoadedTerritoryFactor() {
      loadTerritoryFactor(factors);
      // 850.00 * 1.2000 = 1020.00; tax 35.70; total 1080.70.
      assertThat(rater.rate(AUT_IN_T1).totalPremium()).hasToString("1080.70");
      assertThat(rater.snapshot().rate(AUT_IN_T1).totalPremium()).hasToString("1080.70");
    }
  }
}
