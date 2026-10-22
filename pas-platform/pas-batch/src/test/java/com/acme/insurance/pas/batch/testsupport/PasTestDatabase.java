package com.acme.insurance.pas.batch.testsupport;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

public final class PasTestDatabase {
  private PasTestDatabase() {}

  public static void migratePas(PostgreSQLContainer<?> postgres) {
    postgres.start();
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas("pas")
        .defaultSchema("pas")
        .locations("classpath:db/pas-migration")
        .load()
        .migrate();
  }
}
