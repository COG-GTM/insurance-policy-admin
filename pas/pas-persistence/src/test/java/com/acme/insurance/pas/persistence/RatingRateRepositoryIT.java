package com.acme.insurance.pas.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.RatingParameter;
import com.acme.insurance.pas.domain.RatingParameterCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V20/V21 (AB-522): rate reference tables, the seeded PREMBAT rates and effective dating. */
@Testcontainers
class RatingRateRepositoryIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static JdbcClient jdbc;
  static RatingRateJdbcRepository rates;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/postgresql")
        .load()
        .migrate();
    jdbc = JdbcClient.create(dataSource);
    rates = new RatingRateJdbcRepository(jdbc);
  }

  @Test
  void seededRowsAreTheRatesPrembatHardcodes() {
    LocalDate today = LocalDate.of(2025, 3, 1);
    Map<String, String> base =
        rates.baseRatesInForce(today).stream()
            .collect(Collectors.toMap(BaseRate::policyType, r -> r.baseRate().toPlainString()));
    assertThat(base)
        .containsOnly(
            Map.entry("AUT", "850.00"),
            Map.entry("HOM", "1200.00"),
            Map.entry("COM", "5000.00"),
            Map.entry("LIF", "400.00"),
            Map.entry("HLT", "3500.00"));
    Map<RatingParameterCode, String> params =
        rates.parametersInForce(today).stream()
            .collect(Collectors.toMap(RatingParameter::code, p -> p.value().toPlainString()));
    assertThat(params)
        .containsOnly(
            Map.entry(RatingParameterCode.OTHER_BASE_RATE, "1000.00"),
            Map.entry(RatingParameterCode.TAX_RATE, "0.0350"),
            Map.entry(RatingParameterCode.SURCHARGE_AMT, "25.00"));
  }

  @Test
  void theLatestRowOnOrBeforeTheRatingDateWins() {
    LocalDate change = LocalDate.of(2031, 7, 1);
    rates.insert(new BaseRate("LIF", change, new BigDecimal("410.00")));
    rates.insert(
        new RatingParameter(RatingParameterCode.SURCHARGE_AMT, change, new BigDecimal("30.00")));

    assertThat(lif(change.minusDays(1))).isEqualTo("400.00");
    assertThat(lif(change)).isEqualTo("410.00");
    assertThat(surcharge(change.minusDays(1))).isEqualTo("25.00");
    assertThat(surcharge(change.plusYears(1))).isEqualTo("30.00");
    assertThat(rates.baseRatesInForce(change)).hasSize(5);
  }

  @Test
  void constraintsRejectBadRows() {
    assertThatThrownBy(
            () ->
                jdbc.sql("INSERT INTO pasapp.base_rates VALUES ('AUT', DATE '2040-01-01', -1.00)")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO pasapp.rating_parameters VALUES ('DISCOUNT', DATE '2040-01-01', 1)")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO pasapp.rating_parameters"
                            + " VALUES ('SURCHARGE_AMT', DATE '2040-01-01', 25.005)")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO pasapp.rating_parameters"
                            + " VALUES ('TAX_RATE', DATE '2040-01-01', 10.0000)")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static String lif(LocalDate date) {
    return rates.baseRatesInForce(date).stream()
        .filter(r -> r.policyType().equals("LIF"))
        .findFirst()
        .orElseThrow()
        .baseRate()
        .toPlainString();
  }

  private static String surcharge(LocalDate date) {
    return rates.parametersInForce(date).stream()
        .filter(p -> p.code() == RatingParameterCode.SURCHARGE_AMT)
        .findFirst()
        .orElseThrow()
        .value()
        .toPlainString();
  }
}
