package com.acme.insurance.pas.batch.migration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only connection pool to DB2 {@code ACMEINS}. Deliberately not a {@link DataSource} bean so
 * Spring Boot's auto-configured target datasource (PAS PostgreSQL), Flyway and Spring Batch keep
 * using the target database.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "returns an immutable List.of / shared pool or mapper by design")
public final class SourceDatabase implements AutoCloseable {

  private static final int MAX_POOL = 4;

  private final HikariDataSource dataSource;
  private final String schema;

  public SourceDatabase(MigrationProperties.Source source) {
    if (!source.configured()) {
      throw new IllegalStateException(
          "pas.migration.source.url is not set (PAS_MIGRATION_SOURCE_URL)");
    }
    HikariConfig config = new HikariConfig();
    config.setPoolName("pas-migration-source");
    config.setJdbcUrl(source.url());
    config.setUsername(source.username());
    config.setPassword(source.password());
    if (source.driverClassName() != null && !source.driverClassName().isBlank()) {
      config.setDriverClassName(source.driverClassName());
    }
    config.setReadOnly(true);
    config.setMaximumPoolSize(MAX_POOL);
    this.dataSource = new HikariDataSource(config);
    this.schema = source.schema();
  }

  public DataSource dataSource() {
    return dataSource;
  }

  public JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  /** Qualified source table name, e.g. {@code ACMEINS.POLICIES}. */
  public String table(String name) {
    return schema + "." + name;
  }

  @Override
  public void close() {
    dataSource.close();
  }
}
