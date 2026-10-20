package com.acme.insurance.pas.policy.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.parity.ParityHarness;
import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.rating.JdbcRatingTables;
import com.acme.insurance.pas.rating.PrembatParityAdapter;
import com.acme.insurance.pas.rating.PremiumBreakdown;
import com.acme.insurance.pas.rating.PremiumRatingService;
import com.acme.insurance.pas.rating.RatingDataException;
import com.acme.insurance.pas.rating.RatingRequest;
import com.acme.insurance.pas.rating.RatingSnapshot;
import com.acme.insurance.pas.rating.RatingTables;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AB-522: the rating component wired into pas-policy-service, reading the Flyway-seeded reference
 * tables (V3 factors, V5 base rates / parameters), reproduces every PREMBAT golden to the cent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class RatingTablesIT {

  private static final LocalDate RUN_DATE = LocalDate.of(2024, 6, 1);

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void piiKeys(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
  }

  @Autowired RatingTables tables;
  @Autowired PremiumRatingService rating;
  @Autowired JdbcTemplate jdbc;

  @Test
  void seededTablesMatchThePrembatGolden() {
    assertThat(tables).isInstanceOf(JdbcRatingTables.class);
    assertThat(rating.factorSet()).isEqualTo("PARITY");
    ParityHarness.assertParity(new PrembatParityAdapter(rating.snapshot(RUN_DATE)));
  }

  @Test
  void breakdownFromTheDatabaseCarriesFactorsAndVersion() {
    PremiumBreakdown r =
        rating.rate(
            new RatingRequest(
                "COM", "NY0001", "C01", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)),
            RUN_DATE);
    assertThat(r.baseRate()).isEqualByComparingTo("5000.00");
    assertThat(r.territoryFactor()).isEqualByComparingTo("1.0000");
    assertThat(r.classFactor()).isEqualByComparingTo("1.0000");
    assertThat(r.taxAmount()).isEqualByComparingTo("175.00");
    assertThat(r.surchargeAmount()).isEqualByComparingTo("25.00");
    assertThat(r.totalPremium()).isEqualByComparingTo("5200.00");
    assertThat(r.ratingVersion()).isEqualTo("PREMBAT-3200/PARITY");
  }

  @Test
  void filedFactorSetIsEmptyAndRefusesToRate() {
    assertThatThrownBy(() -> tables.load(RUN_DATE, "FILED"))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("factor set FILED has no territory and/or class factors");
    assertThatThrownBy(() -> tables.load(RUN_DATE, "NOPE"))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("unknown rating factor set");
  }

  @Test
  @Transactional
  void filedFactorsAndNewFilingsApplyOnceLoaded() {
    jdbc.update(
        "INSERT INTO pas.territory_factors VALUES ('FILED', 'NY0001', DATE '2024-01-01', 1.2500),"
            + " ('FILED', '*', DATE '2024-01-01', 1.0000)");
    jdbc.update(
        "INSERT INTO pas.class_factors VALUES ('FILED', 'C01', DATE '2024-01-01', 0.9000),"
            + " ('FILED', '*', DATE '2024-01-01', 1.0000)");
    jdbc.update(
        "INSERT INTO pas.rating_parameters VALUES"
            + " (DATE '2024-01-01', 'FILED-2024-01', 0.0350, 25.00, 1.0000, 1.0000, 0.00)");
    jdbc.update("INSERT INTO pas.base_rates VALUES ('AUT', DATE '2024-07-01', 900.00)");
    assertThatThrownBy(() -> tables.load(LocalDate.of(2024, 7, 1), "PARITY"))
        .isInstanceOf(RatingDataException.class)
        .hasMessageContaining("newer than rating_parameters rate_version FILED-2024-01");
    jdbc.update(
        "INSERT INTO pas.rating_parameters VALUES"
            + " (DATE '2024-07-01', 'RATES-2024-07', 0.0350, 25.00, 1.0000, 1.0000, 0.00)");

    RatingSnapshot june = tables.load(RUN_DATE, "FILED");
    PremiumBreakdown r =
        rating.rate(june, new RatingRequest("AUT", "NY0001  ", "C01", RUN_DATE, RUN_DATE));
    assertThat(r.territoryPremium()).isEqualByComparingTo("1062.50");
    assertThat(r.classPremium()).isEqualByComparingTo("956.25");
    assertThat(r.taxAmount()).isEqualByComparingTo("33.46");
    assertThat(r.totalPremium()).isEqualByComparingTo("1014.71");
    assertThat(r.ratingVersion()).isEqualTo("FILED-2024-01/FILED");

    RatingSnapshot july = tables.load(LocalDate.of(2024, 7, 1), "PARITY");
    assertThat(july.baseRate("AUT")).isEqualByComparingTo("900.00");
    assertThat(july.baseRate("HOM")).isEqualByComparingTo("1200.00");
    assertThat(july.parameters().rateVersion()).isEqualTo("RATES-2024-07");
  }

  @Test
  void referenceTablesRejectValuesThatDoNotFitThePrembatPics() {
    // NUMERIC(9,2) caps the base at 9999999.99, which fits PREM-BASE-RATE S9(7)V9999
    assertThatThrownBy(
            () -> jdbc.update("INSERT INTO pas.base_rates VALUES ('BIG', CURRENT_DATE, 10000000)"))
        .hasMessageContaining("numeric field overflow");
    assertThatThrownBy(
            () -> jdbc.update("INSERT INTO pas.base_rates VALUES ('NEG', CURRENT_DATE, -1)"))
        .hasMessageContaining("ck_base_rate_range");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pas.rating_parameters VALUES"
                        + " (CURRENT_DATE, 'X', 0.0350, 25.00, 0, 1, 0)"))
        .hasMessageContaining("ck_rating_experience_mod_positive");
  }
}
