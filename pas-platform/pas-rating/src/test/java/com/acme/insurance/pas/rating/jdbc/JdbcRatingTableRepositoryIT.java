package com.acme.insurance.pas.rating.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.rating.FactorMode;
import com.acme.insurance.pas.rating.PremiumBreakdown;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.RatingRequest;
import java.nio.file.Path;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcRatingTableRepositoryIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static DataSource dataSource;
  private static JdbcTemplate jdbc;
  private static JdbcRatingTableRepository repository;

  @BeforeAll
  static void migrateAndCreateRepository() {
    PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();
    postgresDataSource.setURL(POSTGRES.getJdbcUrl());
    postgresDataSource.setUser(POSTGRES.getUsername());
    postgresDataSource.setPassword(POSTGRES.getPassword());
    dataSource = postgresDataSource;
    Path migrations =
        Path.of("../pas-policy-service/src/main/resources/db/migration")
            .toAbsolutePath()
            .normalize();
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("pas")
        .defaultSchema("pas")
        .locations("filesystem:" + migrations)
        .load()
        .migrate();
    jdbc = new JdbcTemplate(dataSource);
    repository = new JdbcRatingTableRepository(JdbcClient.create(dataSource));
  }

  @BeforeEach
  void resetDatabaseState() {
    jdbc.update("DELETE FROM pas.rating_parameters WHERE source = 'IT'");
    jdbc.update("UPDATE pas.rating_factor_sets SET active = false WHERE active");
    jdbc.update("UPDATE pas.rating_factor_sets SET active = true WHERE set_code = 'LEGACY'");
  }

  @Test
  void readsQ07SeedsAndUsesCharAndCaseSensitivePolicyTypeMatching() {
    assertThat(repository.findParameters("AUT", LocalDate.of(2024, 10, 15)))
        .get()
        .satisfies(
            parameters -> {
              assertThat(parameters.policyTypeKey()).isEqualTo("AUT");
              assertThat(parameters.basePremium()).isEqualByComparingTo("850.00");
              assertThat(parameters.experienceMod()).isEqualByComparingTo("1.0000");
              assertThat(parameters.scheduleMod()).isEqualByComparingTo("1.0000");
              assertThat(parameters.taxRate()).isEqualByComparingTo("0.0350");
              assertThat(parameters.surchargeAmount()).isEqualByComparingTo("25.00");
            });
    assertThat(repository.findParameters("aut", LocalDate.of(2024, 10, 15)))
        .get()
        .satisfies(parameters -> assertThat(parameters.policyTypeKey()).isEqualTo("*"));
    assertThat(repository.findParameters("AU", LocalDate.of(2024, 10, 15)))
        .get()
        .satisfies(parameters -> assertThat(parameters.policyTypeKey()).isEqualTo("*"));

    assertThat(repository.findParameters("*", LocalDate.of(2024, 10, 15)))
        .get()
        .satisfies(
            parameters -> assertThat(parameters.basePremium()).isEqualByComparingTo("1000.00"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pas.rating_parameters", Integer.class))
        .isEqualTo(6);
  }

  @Test
  void ratesEveryQ07PolicyTypeFromTheMigratedTables() {
    PremiumCalculator calculator = new PremiumCalculator(repository, FactorMode.LEGACY);
    var expectedTotals =
        java.util.Map.of(
            "AUT", "904.75",
            "HOM", "1267.00",
            "COM", "5200.00",
            "LIF", "439.00",
            "HLT", "3647.50",
            "", "1060.00",
            "XYZ", "1060.00");

    expectedTotals.forEach(
        (policyType, total) ->
            assertThat(
                    calculator
                        .calculate(
                            new RatingRequest(policyType, LocalDate.of(2024, 10, 15), null, null))
                        .totalPremium())
                .isEqualByComparingTo(total));
  }

  @Test
  void selectsExactParameterBeforeNewerFallbackAndIgnoresFutureRows() {
    jdbc.update(
        "INSERT INTO pas.rating_parameters (policy_type, effective_date, base_premium, experience_mod, schedule_mod, tax_rate, surcharge_amount, source) VALUES ('*', DATE '2024-01-01', 2000.00, 1.0000, 1.0000, 0.0000, 0.00, 'IT')");
    jdbc.update(
        "INSERT INTO pas.rating_parameters (policy_type, effective_date, base_premium, experience_mod, schedule_mod, tax_rate, surcharge_amount, source) VALUES ('AUT', DATE '2050-01-01', 9000.00, 1.0000, 1.0000, 0.0000, 0.00, 'IT')");

    assertThat(repository.findParameters("AUT", LocalDate.of(2024, 10, 15)))
        .get()
        .satisfies(
            parameters -> assertThat(parameters.basePremium()).isEqualByComparingTo("850.00"));
    assertThat(repository.findParameters("AUT", LocalDate.of(2060, 1, 1)))
        .get()
        .satisfies(
            parameters -> assertThat(parameters.basePremium()).isEqualByComparingTo("9000.00"));
  }

  @Test
  void resolvesActiveFactorsAndRatesLegacyAndIllustrativeModes() {
    assertThat(repository.findActiveFactorSet()).contains("LEGACY");
    assertThat(
            repository
                .findTerritoryFactor("LEGACY", "NY0001", LocalDate.of(2024, 10, 15))
                .orElseThrow())
        .isEqualByComparingTo("1.0000");

    jdbc.update("UPDATE pas.rating_factor_sets SET active = false WHERE set_code = 'LEGACY'");
    jdbc.update("UPDATE pas.rating_factor_sets SET active = true WHERE set_code = 'ILLUSTRATIVE'");
    assertThat(repository.findActiveFactorSet()).contains("ILLUSTRATIVE");

    PremiumCalculator calculator = new PremiumCalculator(repository, FactorMode.TABLES);
    PremiumBreakdown tables =
        calculator.calculate(
            new RatingRequest("AUT", LocalDate.of(2024, 10, 15), "NY0001", "PPA01"));
    assertThat(tables.territoryPremium()).isEqualByComparingTo("1062.50");
    assertThat(tables.classPremium()).isEqualByComparingTo("1115.62");
    assertThat(tables.taxAmount()).isEqualByComparingTo("39.04");
    assertThat(tables.totalPremium()).isEqualByComparingTo("1179.66");

    PremiumBreakdown legacy =
        new PremiumCalculator(repository, FactorMode.LEGACY)
            .calculate(new RatingRequest("AUT", LocalDate.of(2024, 10, 15), "NY0001", "PPA01"));
    assertThat(legacy.totalPremium()).isEqualByComparingTo("904.75");
  }
}
