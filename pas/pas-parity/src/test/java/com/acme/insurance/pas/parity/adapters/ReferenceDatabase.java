package com.acme.insurance.pas.parity.adapters;

import com.acme.insurance.pas.persistence.RatingFactorJdbcRepository;
import com.acme.insurance.pas.persistence.RatingRateJdbcRepository;
import com.acme.insurance.pas.rating.PremiumRater;
import com.acme.insurance.pas.rating.RatingFactorMode;
import com.acme.insurance.pas.rating.RatingReferenceData;
import com.acme.insurance.pas.rating.ReferenceRatingTables;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The PostgreSQL schema the rating component reads in production, migrated by the real Flyway
 * scripts (including V21 rate rows) in a throwaway container, shared by the parity tests. Rating
 * through it proves the reference-table values, not test constants, reproduce the golden files.
 */
public final class ReferenceDatabase {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static JdbcClient jdbc;

  private ReferenceDatabase() {}

  public static synchronized JdbcClient jdbc() {
    if (jdbc == null) {
      POSTGRES.start();
      DriverManagerDataSource dataSource =
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration/postgresql")
          .load()
          .migrate();
      jdbc = JdbcClient.create(dataSource);
    }
    return jdbc;
  }

  /** The migrated reference database container, for adapters that run a whole application. */
  public static synchronized PostgreSQLContainer<?> container() {
    jdbc();
    return POSTGRES;
  }

  /** A rater over the reference tables, wired the way the applications wire it. */
  public static PremiumRater rater(RatingFactorMode mode) {
    RatingRateJdbcRepository rates = new RatingRateJdbcRepository(jdbc());
    RatingFactorJdbcRepository factors = new RatingFactorJdbcRepository(jdbc());
    RatingReferenceData data =
        RatingReferenceData.of(rates::baseRatesInForce, rates::parametersInForce, rates::history);
    return new PremiumRater(
        ReferenceRatingTables.snapshotOf(data),
        mode.source(factors::territoryFactor, factors::classFactor));
  }
}
