package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.persistence.DatabaseVendor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Opens the DB2 and PostgreSQL connections of the migration jobs on first use. They are not Spring
 * {@link DataSource} beans on purpose: a DataSource bean would switch off the auto-configured batch
 * datasource of every other job.
 */
public class MigrationConnections implements DisposableBean {

  /** The two sides of the migration. */
  public enum Side {
    DB2,
    POSTGRES
  }

  private final MigrationProperties properties;
  private final Map<Side, HikariDataSource> pools = new EnumMap<>(Side.class);

  public MigrationConnections(MigrationProperties properties) {
    this.properties = properties;
  }

  public synchronized DataSource dataSource(Side side) {
    return pools.computeIfAbsent(side, this::open);
  }

  public JdbcClient client(Side side) {
    return JdbcClient.create(dataSource(side));
  }

  public DatabaseVendor vendor(Side side) {
    try (Connection c = dataSource(side).getConnection()) {
      return DatabaseVendor.fromProductName(c.getMetaData().getDatabaseProductName());
    } catch (SQLException e) {
      throw new IllegalStateException("cannot read database metadata of " + side, e);
    }
  }

  private HikariDataSource open(Side side) {
    MigrationProperties.Connection settings =
        side == Side.DB2 ? properties.db2() : properties.postgres();
    if (settings == null || !settings.configured()) {
      throw new IllegalStateException(
          "pas.migration." + side.name().toLowerCase(java.util.Locale.ROOT) + ".url is not set");
    }
    HikariConfig config = new HikariConfig();
    config.setPoolName("pas-migration-" + side.name().toLowerCase(java.util.Locale.ROOT));
    config.setJdbcUrl(settings.url());
    config.setUsername(settings.username());
    config.setPassword(settings.password());
    config.setMaximumPoolSize(2);
    config.setAutoCommit(true);
    return new HikariDataSource(config);
  }

  @Override
  public synchronized void destroy() {
    pools.values().forEach(HikariDataSource::close);
    pools.clear();
  }
}
