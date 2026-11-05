package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * AB-529 end to end on two PostgreSQL 16 containers: {@code legacy} stands in for DB2 ACMEINS (same
 * tables, keys and defaults as the frozen DDL via Flyway V10-V13), {@code target} is the PostgreSQL
 * target that also holds acmeins_shadow and the batch job repository. Covers the bulk load, the
 * delta sync (insert, update, delete), reconciliation finding planted differences, the
 * system-of-record guards and a rollback rehearsal back to the legacy side.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.flyway.enabled=true"})
class MigrationJobsIT {

  @Container
  static final PostgreSQLContainer<?> LEGACY =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  @Container
  static final PostgreSQLContainer<?> TARGET =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

  @TempDir static Path reports;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", TARGET::getJdbcUrl);
    registry.add("spring.datasource.username", TARGET::getUsername);
    registry.add("spring.datasource.password", TARGET::getPassword);
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/postgresql");
    registry.add("pas.migration.db2.url", LEGACY::getJdbcUrl);
    registry.add("pas.migration.db2.username", LEGACY::getUsername);
    registry.add("pas.migration.db2.password", LEGACY::getPassword);
    registry.add("pas.migration.postgres.url", TARGET::getJdbcUrl);
    registry.add("pas.migration.postgres.username", TARGET::getUsername);
    registry.add("pas.migration.postgres.password", TARGET::getPassword);
    registry.add("pas.migration.report-dir", () -> reports.toString());
    registry.add("pas.migration.batch-size", () -> "7");
  }

  static JdbcTemplate legacy;

  @Autowired JdbcTemplate target;
  @Autowired JobLauncher launcher;
  @Autowired JobRepository jobRepository;

  @Autowired
  @Qualifier(MigrationJobsConfiguration.BULK_LOAD_JOB)
  Job bulkLoad;

  @Autowired
  @Qualifier(MigrationJobsConfiguration.DELTA_SYNC_JOB)
  Job deltaSync;

  @Autowired
  @Qualifier(MigrationJobsConfiguration.RECONCILIATION_JOB)
  Job reconciliation;

  @Autowired
  @Qualifier(MigrationJobsConfiguration.REVERSE_SYNC_JOB)
  Job reverseSync;

  @Autowired
  @Qualifier(MigrationJobsConfiguration.SYSTEM_OF_RECORD_JOB)
  Job systemOfRecord;

  @BeforeAll
  static void migrateLegacy() {
    Flyway.configure()
        .dataSource(LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword())
        .locations("classpath:db/migration/postgresql")
        .load()
        .migrate();
    legacy =
        new JdbcTemplate(
            new DriverManagerDataSource(
                LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword()));
  }

  @BeforeEach
  void reset() {
    String tables =
        "acmeins.underwriting_decisions, acmeins.endorsements, acmeins.premiums,"
            + " acmeins.coverages, acmeins.policies, acmeins.policy_holders,"
            + " acmeins.territory_factors";
    legacy.execute("TRUNCATE " + tables);
    target.execute("TRUNCATE " + tables);
    target.execute(
        "TRUNCATE acmeins_shadow.recon_difference, acmeins_shadow.recon_check,"
            + " acmeins_shadow.sync_table_result, acmeins_shadow.migration_run");
    target.update("UPDATE acmeins_shadow.system_of_record SET system_name = 'DB2' WHERE id = 1");
    legacy.execute("ALTER SEQUENCE acmeins.policy_seq RESTART");
    target.execute("ALTER SEQUENCE acmeins.policy_seq RESTART");
    MigrationFixtures.seed(legacy, 40);
  }

  @Test
  void bulkLoadCopiesAllSevenTablesAndReconcilesClean() throws Exception {
    legacy.queryForObject("SELECT setval('acmeins.policy_seq', 1000042)", Long.class);

    JobExecution run = launch(bulkLoad, Map.of());

    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(run.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
    for (MigrationTable table : MigrationTable.values()) {
      assertThat(count(target, table))
          .as(table.name())
          .isEqualTo(count(legacy, table))
          .isPositive();
    }
    assertThat(latestRun()).containsEntry("status", "MATCH");
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM acmeins_shadow.recon_check WHERE status = 'DIFF'",
                Long.class))
        .isZero();
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM acmeins_shadow.recon_check WHERE kind = 'TOTAL'", Long.class))
        .isGreaterThan(10);
    assertThat(target.queryForObject("SELECT nextval('acmeins.policy_seq')", Long.class))
        .isEqualTo(1000042 + 100);
    try (var files = Files.list(reports)) {
      assertThat(files.map(p -> p.getFileName().toString()).toList())
          .anyMatch(n -> n.endsWith(".json"))
          .anyMatch(n -> n.endsWith(".md"));
    }
    Map<String, Object> summary =
        target.queryForMap(
            "SELECT failed_checks, record_differences FROM acmeins_shadow.recon_daily_summary"
                + " ORDER BY run_id DESC LIMIT 1");
    assertThat(((Number) summary.get("failed_checks")).longValue()).isZero();
    assertThat(((Number) summary.get("record_differences")).longValue()).isZero();
  }

  @Test
  void bulkLoadRefusesANonEmptyTarget() throws Exception {
    assertThat(launch(bulkLoad, Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);

    JobExecution again = launch(bulkLoad, Map.of());

    assertThat(again.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(again.getAllFailureExceptions().get(0)).hasMessageContaining("not empty");
    assertThat(launch(bulkLoad, Map.of("resume", "true")).getStatus())
        .isEqualTo(BatchStatus.COMPLETED);
  }

  @Test
  void deltaSyncAppliesInsertsUpdatesAndDeletesIncludingTablesWithoutTimestamps() throws Exception {
    launch(bulkLoad, Map.of());
    legacy.update(
        "UPDATE acmeins.policies SET total_premium = total_premium + 10.25 WHERE policy_number = ?",
        MigrationFixtures.policy(3));
    legacy.update(
        "UPDATE acmeins.premiums SET tax_amt = 1.11 WHERE policy_number = ?",
        MigrationFixtures.policy(4));
    legacy.update(
        "DELETE FROM acmeins.coverages WHERE policy_number = ? AND sequence_num = 2",
        MigrationFixtures.policy(5));
    legacy.update(
        "DELETE FROM acmeins.underwriting_decisions WHERE policy_number = ?",
        MigrationFixtures.policy(6));
    MigrationFixtures.seedPolicy(legacy, 41);

    JobExecution run = launch(deltaSync, Map.of());

    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(latestRun()).containsEntry("status", "MATCH");
    Map<String, Object> policies =
        target.queryForMap(
            "SELECT inserted, updated, deleted FROM acmeins_shadow.sync_table_result"
                + " WHERE table_name = 'POLICIES' AND run_id = ?",
            latestRun().get("run_id"));
    assertThat(policies).containsEntry("inserted", 1L).containsEntry("updated", 1L);
    assertThat(
            target.queryForObject(
                "SELECT deleted FROM acmeins_shadow.sync_table_result"
                    + " WHERE table_name = 'COVERAGES' AND run_id = ?",
                Long.class,
                latestRun().get("run_id")))
        .isEqualTo(1);
    assertThat(
            target.queryForObject(
                "SELECT total_premium FROM acmeins.policies WHERE policy_number = ?",
                BigDecimal.class,
                MigrationFixtures.policy(3)))
        .isEqualByComparingTo(
            legacy.queryForObject(
                "SELECT total_premium FROM acmeins.policies WHERE policy_number = ?",
                BigDecimal.class,
                MigrationFixtures.policy(3)));
    for (MigrationTable table : MigrationTable.values()) {
      assertThat(count(target, table)).as(table.name()).isEqualTo(count(legacy, table));
    }
  }

  @Test
  void reconciliationFindsCountTotalAndRecordDifferences() throws Exception {
    launch(bulkLoad, Map.of());
    String changed = MigrationFixtures.policy(7);
    target.update(
        "UPDATE acmeins.policies SET total_premium = total_premium + 1 WHERE policy_number = ?",
        changed);
    target.update(
        "DELETE FROM acmeins.coverages WHERE policy_number = ? AND sequence_num = 1",
        MigrationFixtures.policy(8));
    target.update(
        "UPDATE acmeins.premiums SET calc_by = 'TAMPERED' WHERE policy_number = ?",
        MigrationFixtures.policy(9));

    JobExecution run = launch(reconciliation, Map.of());

    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(run.getExitStatus().getExitCode()).isEqualTo("DIFFERENCES");
    assertThat(latestRun()).containsEntry("status", "DIFFERENCES");
    assertThat(
            target.queryForObject(
                "SELECT record_differences FROM acmeins_shadow.recon_daily_summary WHERE run_id = ?",
                Long.class,
                latestRun().get("run_id")))
        .isPositive();
    List<Map<String, Object>> differences =
        target.queryForList(
            "SELECT table_name, record_key, difference FROM acmeins_shadow.recon_difference"
                + " ORDER BY table_name");
    assertThat(differences)
        .extracting(d -> d.get("table_name") + ":" + d.get("difference"))
        .containsExactlyInAnyOrder(
            "COVERAGES:MISSING_IN_PG",
            "PREMIUMS:HASH_MISMATCH",
            "PREMIUMS:HASH_MISMATCH",
            "POLICIES:HASH_MISMATCH");
    assertThat(differences)
        .filteredOn(d -> d.get("table_name").equals("POLICIES"))
        .extracting(d -> d.get("record_key"))
        .containsExactly(changed);
    String dimension = MigrationFixtures.type(7) + "/" + MigrationFixtures.branch(7);
    assertThat(
            target.queryForList(
                "SELECT check_id FROM acmeins_shadow.recon_check WHERE status = 'DIFF'",
                String.class))
        .contains(
            "COUNT:COVERAGES:ALL:ROWS",
            "TOTAL:POLICIES:" + dimension + ":TOTAL_PREMIUM",
            "HASH:POLICIES:ALL:MATCHING_RECORDS");
  }

  @Test
  void forwardSyncRefusesOnceTheTargetIsTheSystemOfRecord() throws Exception {
    launch(bulkLoad, Map.of());
    cutover();

    JobExecution run = launch(deltaSync, Map.of());

    assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(run.getExitStatus().getExitDescription()).contains("system of record is PG");
    assertThat(launch(reverseSync, Map.of()).getAllFailureExceptions().get(0))
        .hasMessageContaining("confirmRollback=YES");
  }

  @Test
  void rollbackRehearsalPointsBackAtTheLegacyDatabaseWithinTheWindow() throws Exception {
    launch(bulkLoad, Map.of());
    cutover();
    // Writes made on PostgreSQL after cutover: a new policy numbered from the PostgreSQL
    // sequence, a premium change and a removed endorsement.
    long issued = target.queryForObject("SELECT nextval('acmeins.policy_seq')", Long.class);
    MigrationFixtures.seedPolicy(target, (int) (issued % 1000));
    target.update(
        "UPDATE acmeins.policies SET total_premium = 999.99 WHERE policy_number = ?",
        MigrationFixtures.policy(11));
    target.update(
        "DELETE FROM acmeins.endorsements WHERE policy_number = ?", MigrationFixtures.policy(12));
    Instant start = Instant.now();

    JobExecution run = launch(reverseSync, Map.of("confirmRollback", "YES"));

    Duration elapsed = Duration.between(start, Instant.now());
    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(elapsed).isLessThan(Duration.ofMinutes(5));
    for (MigrationTable table : MigrationTable.values()) {
      assertThat(count(legacy, table)).as(table.name()).isEqualTo(count(target, table));
    }
    assertThat(
            legacy.queryForObject(
                "SELECT total_premium FROM acmeins.policies WHERE policy_number = ?",
                BigDecimal.class,
                MigrationFixtures.policy(11)))
        .isEqualByComparingTo("999.99");
    assertThat(legacy.queryForObject("SELECT nextval('acmeins.policy_seq')", Long.class))
        .isGreaterThan(issued);
    assertThat(
            target.queryForObject(
                "SELECT system_name FROM acmeins_shadow.system_of_record", String.class))
        .isEqualTo("DB2");
    assertThat(latestRun())
        .containsEntry("status", "MATCH")
        .containsEntry("direction", "PG_TO_DB2");
    assertThat(launch(deltaSync, Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
  }

  @Test
  void deltaSyncCarriesTrailingBlankOnlyVarcharEdits() throws Exception {
    launch(bulkLoad, Map.of());
    legacy.update(
        "UPDATE acmeins.policy_holders SET last_name = 'TESTLAST3 ' WHERE last_name = 'TESTLAST3'");

    assertThat(launch(deltaSync, Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);

    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM acmeins.policy_holders WHERE last_name = 'TESTLAST3 '",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void cutoverWaitsForARunningSync() throws Exception {
    launch(bulkLoad, Map.of());
    JobExecution running =
        jobRepository.createJobExecution(
            MigrationJobsConfiguration.DELTA_SYNC_JOB,
            new JobParametersBuilder().addLong("it", System.nanoTime()).toJobParameters());
    running.setStartTime(LocalDateTime.now());
    running.setStatus(BatchStatus.STARTED);
    jobRepository.update(running);

    JobExecution refused =
        launch(
            systemOfRecord,
            Map.of("system", "PG", "confirm", "YES", "reason", "IT cutover", "changedBy", "it"));
    assertThat(refused.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(
            target.queryForObject(
                "SELECT system_name FROM acmeins_shadow.system_of_record", String.class))
        .isEqualTo("DB2");

    running.setEndTime(LocalDateTime.now());
    running.setStatus(BatchStatus.FAILED);
    jobRepository.update(running);
    cutover();
  }

  @Test
  void rollbackOfSameDayDecisionsNeedsAnExplicitCollapse() throws Exception {
    launch(bulkLoad, Map.of());
    cutover();
    String policy = MigrationFixtures.policy(7);
    target.update(
        "INSERT INTO acmeins.underwriting_decisions (policy_number, decision_date, decision_code,"
            + " risk_score, decision_reason, underwriter_id, created_timestamp)"
            + " VALUES (?, ?, 'AP', 43, 'LATER', 'UW002', localtimestamp + interval '1 hour')",
        policy,
        Date.valueOf("2026-01-01"));

    assertThat(launch(reverseSync, Map.of("confirmRollback", "YES")).getStatus())
        .isEqualTo(BatchStatus.FAILED);
    JobExecution run =
        launch(
            reverseSync,
            Map.of(
                "confirmRollback",
                "YES",
                MigrationJobsConfiguration.COLLAPSE_SAME_DAY_DECISIONS,
                "YES"));

    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(
            legacy.queryForList(
                "SELECT decision_reason FROM acmeins.underwriting_decisions WHERE policy_number = ?",
                String.class,
                policy))
        .containsExactly("LATER");
    assertThat(
            target.queryForObject(
                "SELECT COUNT(*) FROM acmeins.underwriting_decisions WHERE policy_number = ?",
                Integer.class,
                policy))
        .isEqualTo(2);
    assertThat(
            target.queryForObject(
                "SELECT system_name FROM acmeins_shadow.system_of_record", String.class))
        .isEqualTo("DB2");
  }

  private void cutover() throws Exception {
    assertThat(launch(systemOfRecord, Map.of("system", "PG", "confirm", "NO")).getStatus())
        .isEqualTo(BatchStatus.FAILED);
    JobExecution cut =
        launch(
            systemOfRecord,
            Map.of("system", "PG", "confirm", "YES", "reason", "IT cutover", "changedBy", "it"));
    assertThat(cut.getStatus()).isEqualTo(BatchStatus.COMPLETED);
  }

  private JobExecution launch(Job job, Map<String, String> extra) throws Exception {
    JobParametersBuilder params =
        new JobParametersBuilder()
            .addLong(MigrationParameters.RUN_ID, System.nanoTime())
            .addString(MigrationParameters.BUSINESS_DATE, LocalDate.of(2026, 11, 5).toString());
    extra.forEach(params::addString);
    return launcher.run(job, params.toJobParameters());
  }

  private Map<String, Object> latestRun() {
    return target.queryForMap(
        "SELECT run_id, status, direction FROM acmeins_shadow.migration_run"
            + " ORDER BY run_id DESC LIMIT 1");
  }

  private static long count(JdbcTemplate jdbc, MigrationTable table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table.qualifiedName(), Long.class);
  }

  static Date date(String iso) {
    return Date.valueOf(iso);
  }
}
