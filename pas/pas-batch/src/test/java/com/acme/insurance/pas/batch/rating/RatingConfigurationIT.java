package com.acme.insurance.pas.batch.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.TerritoryFactor;
import com.acme.insurance.pas.persistence.RatingFactorJdbcRepository;
import com.acme.insurance.pas.rating.PremiumRater;
import com.acme.insurance.pas.rating.RatingRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The batch application's PremiumRater over the migrated schema, flag off and on (AB-522). */
class RatingConfigurationIT {

  static final String FLYWAY_LOCATIONS = "spring.flyway.locations=classpath:db/migration/{vendor}";
  // pas-batch has several jobs (AB-527); none must run when the context starts.
  static final String NO_JOB_RUN = "spring.batch.job.enabled=false";
  static final LocalDate RUN = LocalDate.of(2025, 3, 1);
  static final RatingRequest AUT_IN_T1 = new RatingRequest("AUT", RUN, "T1", null);

  @TestConfiguration(proxyBeanMethods = false)
  static class Postgres {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
      return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
  }

  static void loadTerritoryFactor(RatingFactorJdbcRepository factors) {
    factors.insert(new TerritoryFactor("T1", LocalDate.of(2025, 1, 1), new BigDecimal("1.2000")));
  }

  @Nested
  @SpringBootTest(properties = {"spring.flyway.enabled=true", FLYWAY_LOCATIONS, NO_JOB_RUN})
  @Import(Postgres.class)
  class DefaultFlag {
    @Autowired PremiumRater rater;
    @Autowired RatingFactorJdbcRepository factors;

    @Test
    void ratesFromTheReferenceTablesWithNeutralFactors() {
      loadTerritoryFactor(factors);
      assertThat(rater.rate(AUT_IN_T1).totalPremium()).hasToString("904.75");
      assertThat(rater.rate(RatingRequest.of("XYZ", RUN)).totalPremium()).hasToString("1060.00");
    }
  }

  @Nested
  @SpringBootTest(
      properties = {
        "spring.flyway.enabled=true",
        FLYWAY_LOCATIONS,
        NO_JOB_RUN,
        "pas.rating.factor-source=table-driven"
      })
  @Import(Postgres.class)
  class TableDrivenFlag {
    @Autowired PremiumRater rater;
    @Autowired RatingFactorJdbcRepository factors;

    @Test
    void appliesTheLoadedTerritoryFactor() {
      loadTerritoryFactor(factors);
      // 850.00 * 1.2000 = 1020.00; tax 35.70; total 1080.70.
      assertThat(rater.rate(AUT_IN_T1).totalPremium()).hasToString("1080.70");
      assertThat(rater.rate(RatingRequest.of("AUT", RUN)).totalPremium()).hasToString("904.75");
    }
  }
}
