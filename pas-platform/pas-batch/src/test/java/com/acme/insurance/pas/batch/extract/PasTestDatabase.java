package com.acme.insurance.pas.batch.extract;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

final class PasTestDatabase {
  private PasTestDatabase() {}

  static void migratePas(PostgreSQLContainer<?> postgres) {
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
