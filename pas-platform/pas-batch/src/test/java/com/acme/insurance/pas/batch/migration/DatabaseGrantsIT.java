package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DatabaseGrantsIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void cutoverGrantsKeepLegacyReadOnlyAndMoveWritesToPostgres() throws Exception {
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
    jdbc.update(
        """
        INSERT INTO pas.policies
            (policy_number, policy_type, effective_date, expiry_date, policyholder_id)
        VALUES ('GRANT0000001', 'AUT', DATE '2025-01-01', DATE '2026-01-01', 'GRANT00001')
        """);
    jdbc.execute("TRUNCATE pas.change_log");

    executeResource(jdbc, "grants/roles.sql");
    executeResource(jdbc, "grants/pre-cutover.sql");
    assertDenied("pas_service", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertDenied("pas_service", "UPDATE pas.policies SET policy_status = policy_status");
    assertDenied("pas_batch", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertDenied("pas_batch", "UPDATE pas.policies SET policy_status = policy_status");
    assertAllowed("pas_sync", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertAllowed("pas_sync", "SELECT nextval('pas.policy_seq')");
    assertAllowed("pas_sync", "SELECT nextval('pas.underwriting_decision_seq')");
    assertDenied(
        "pas_sync",
        "INSERT INTO pas.change_log (table_name, record_key) VALUES ('GRANTS_IT', 'DIRECT')");
    assertDenied("pas_sync", "SELECT nextval('pas.change_log_id_seq')");
    assertDenied("pas_batch", "DELETE FROM pas.change_log WHERE record_key = 'BATCH'");
    assertDenied(
        "pas_sync",
        "UPDATE pas.policies SET policy_status = 'AC' WHERE policy_number = 'GRANT0000001'");
    assertTaggedAllowed(
        "pas_sync",
        "UPDATE pas.policies SET policy_status = 'AC' WHERE policy_number = 'GRANT0000001'");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class)).isZero();
    assertAllowed("pas_readonly", "SELECT policy_number FROM pas.policies");

    executeResource(jdbc, "grants/post-cutover.sql");
    int loggedBeforeServiceWrite =
        jdbc.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class);
    assertAllowed(
        "pas_service",
        "UPDATE pas.policies SET policy_status = 'PN' WHERE policy_number = 'GRANT0000001'");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class))
        .isEqualTo(loggedBeforeServiceWrite + 1);
    assertAllowed("pas_service", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertAllowed("pas_service", "UPDATE pas.policies SET policy_status = policy_status");
    assertAllowed("pas_batch", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertAllowed("pas_batch", "UPDATE pas.policies SET policy_status = policy_status");
    assertAllowed(
        "pas_batch",
        "INSERT INTO pas_batch.sync_watermark "
            + "(table_name, direction, watermark_value, synced_through, updated_at) "
            + "VALUES ('GRANTS_IT', 'REVERSE', now(), now(), now()) "
            + "ON CONFLICT (table_name, direction) DO NOTHING");
    assertAllowed("pas_batch", "SELECT nextval('pas_batch.batch_job_seq')");
    assertAllowed(
        "pas_batch",
        "INSERT INTO pas.change_log (table_name, record_key) VALUES ('GRANTS_IT', 'BATCH')");
    assertAllowed("pas_batch", "SELECT nextval('pas.change_log_id_seq')");
    assertBatchLoginCanRunAJobAndPruneChangeLog();
    assertDenied(
        "pas_sync",
        "INSERT INTO pas.change_log (table_name, record_key) VALUES ('GRANTS_IT', 'POST')");
    assertDenied("pas_sync", "SELECT nextval('pas.change_log_id_seq')");
    assertDenied("pas_sync", "INSERT INTO pas.policies SELECT * FROM pas.policies WHERE false");
    assertDenied("pas_sync", "UPDATE pas.policies SET policy_status = policy_status");
    assertAllowed("pas_readonly", "SELECT policy_number FROM pas.policies");
  }

  private static void assertBatchLoginCanRunAJobAndPruneChangeLog() throws Exception {
    JdbcTemplate admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    String password = UUID.randomUUID().toString().replace("-", "");
    String createRole =
        admin.queryForObject(
            "SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', ?, ?)",
            String.class, "pas_batch_it_login", password);
    admin.execute(createRole);
    admin.execute("GRANT pas_batch TO pas_batch_it_login");

    DataSource dataSource =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "pas_batch_it_login", password);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    JobRepositoryFactoryBean repositoryFactory = new JobRepositoryFactoryBean();
    repositoryFactory.setDataSource(dataSource);
    repositoryFactory.setTransactionManager(transactionManager);
    repositoryFactory.setDatabaseType("POSTGRES");
    repositoryFactory.setTablePrefix("pas_batch.BATCH_");
    repositoryFactory.afterPropertiesSet();

    var repository = repositoryFactory.getObject();
    TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
    launcher.setJobRepository(repository);
    launcher.afterPropertiesSet();
    Job job =
        new JobBuilder("databaseGrantsBatchJob", repository)
            .start(
                new StepBuilder("databaseGrantsBatchStep", repository)
                    .tasklet(
                        (contribution, chunkContext) ->
                            org.springframework.batch.repeat.RepeatStatus.FINISHED,
                        transactionManager)
                    .build())
            .build();
    JobExecution execution =
        launcher.run(
            job,
            new JobParametersBuilder()
                .addString("runId", UUID.randomUUID().toString())
                .toJobParameters());
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(
            new JdbcTemplate(dataSource)
                .update(
                    "DELETE FROM pas.change_log "
                        + "WHERE table_name = 'GRANTS_IT' AND record_key = 'BATCH'"))
        .isEqualTo(1);
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

  private static void assertTaggedAllowed(String role, String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET ROLE " + role);
      statement.execute("SET LOCAL pas.change_origin = 'forward-sync'");
      statement.execute(sql);
      connection.commit();
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
