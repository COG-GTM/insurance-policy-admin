package com.acme.insurance.pas.parity.prembat;

import com.acme.insurance.pas.parity.ParityHarness;
import com.acme.insurance.pas.rating.FactorMode;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.jdbc.JdbcRatingTableRepository;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PrembatParityTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static PrembatParityAdapter adapter;

  @BeforeAll
  static void setUpRatingDatabase() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
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
    adapter =
        new PrembatParityAdapter(
            new PremiumCalculator(
                new JdbcRatingTableRepository(JdbcClient.create(dataSource)), FactorMode.LEGACY));
  }

  @TestFactory
  Stream<DynamicTest> matchesCobolFixtures() {
    return ParityHarness.dynamicTests(adapter);
  }
}
