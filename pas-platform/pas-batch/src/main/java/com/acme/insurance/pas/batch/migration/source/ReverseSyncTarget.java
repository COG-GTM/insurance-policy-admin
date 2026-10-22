package com.acme.insurance.pas.batch.migration.source;

import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The target owns and closes the injected connection pool.")
public final class ReverseSyncTarget implements AutoCloseable {
  private final HikariDataSource pool;

  public ReverseSyncTarget(HikariDataSource pool) {
    this.pool = pool;
  }

  public void transaction(Consumer<Connection> work) {
    try (Connection connection = pool.getConnection()) {
      connection.setReadOnly(false);
      connection.setAutoCommit(false);
      try {
        work.accept(connection);
        connection.commit();
      } catch (RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("DB2 reverse-sync transaction failed", exception);
    }
  }

  private static void rollback(Connection connection, RuntimeException failure) {
    try {
      connection.rollback();
    } catch (SQLException exception) {
      failure.addSuppressed(exception);
    }
  }

  @Override
  public void close() {
    pool.close();
  }
}
