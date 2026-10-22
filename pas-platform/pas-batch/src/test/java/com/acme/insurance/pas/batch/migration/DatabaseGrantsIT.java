package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DatabaseGrantsIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void cutoverGrantsKeepLegacyReadOnlyAndMoveWritesToPostgres() throws IOException {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/pas-migration")
        .schemas("pas")
        .defaultSchema("pas")
        .load()
        .migrate();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration/batch")
        .schemas("pas_batch")
        .defaultSchema("pas_batch")
        .load()
        .migrate();
    JdbcTemplate jdbc =
        new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

    executeResource(jdbc, "grants/roles.sql");
    executeResource(jdbc, "grants/pre-cutover.sql");
    assertDenied("pas_service", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertDenied("pas_service", "UPDATE pas.policies SET policy_status = policy_status");
    assertDenied("pas_batch", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertDenied("pas_batch", "UPDATE pas.policies SET policy_status = policy_status");
    assertAllowed("pas_sync", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertAllowed("pas_sync", "UPDATE pas.policies SET policy_status = policy_status");
    assertAllowed("pas_readonly", "SELECT policy_number FROM pas.policies");

    executeResource(jdbc, "grants/post-cutover.sql");
    assertAllowed("pas_service", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertAllowed("pas_service", "UPDATE pas.policies SET policy_status = policy_status");
    assertAllowed("pas_batch", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertAllowed("pas_batch", "UPDATE pas.policies SET policy_status = policy_status");
    assertDenied("pas_sync", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertDenied("pas_sync", "UPDATE pas.policies SET policy_status = policy_status");
    assertAllowed("pas_readonly", "SELECT policy_number FROM pas.policies");
  }

  private static void assertAllowed(String role, String sql) {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("SET ROLE " + role);
      statement.execute(sql);
      statement.execute("RESET ROLE");
    } catch (SQLException exception) {
      throw new AssertionError("Expected " + role + " to run: " + sql, exception);
    }
  }

  private static void assertDenied(String role, String sql) {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("SET ROLE " + role);
      assertThatThrownBy(() -> statement.execute(sql))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("permission denied");
    } catch (SQLException exception) {
      throw new AssertionError("Could not test grants for " + role, exception);
    }
  }

  private static void executeResource(JdbcTemplate jdbc, String name) throws IOException {
    try (var input = DatabaseGrantsIT.class.getClassLoader().getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalStateException("Missing test resource " + name);
      }
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      StringBuilder statement = new StringBuilder();
      boolean inDollarQuotedBlock = false;
      for (int index = 0; index < sql.length(); index++) {
        if (sql.startsWith("$$", index)) {
          inDollarQuotedBlock = !inDollarQuotedBlock;
          statement.append("$$");
          index++;
        } else if (sql.charAt(index) == ';' && !inDollarQuotedBlock) {
          if (!statement.toString().isBlank()) {
            jdbc.execute(statement.toString());
            statement.setLength(0);
          }
        } else {
          statement.append(sql.charAt(index));
        }
      }
      if (!statement.toString().isBlank()) {
        jdbc.execute(statement.toString());
      }
    }
  }
}
