package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.reconcile.ReconciliationService;
import com.acme.insurance.pas.batch.migration.report.CheckReport;
import com.acme.insurance.pas.batch.migration.report.MigrationStatusEndpoint;
import com.acme.insurance.pas.batch.migration.triage.Classification;
import com.acme.insurance.pas.batch.migration.triage.Difference;
import com.acme.insurance.pas.batch.migration.triage.TriageRegister;
import com.acme.insurance.pas.batch.support.ParityFixtures;
import com.acme.insurance.pas.batch.support.SourceStandIn;
import com.acme.insurance.pas.common.pii.BlindIndexer;
import com.acme.insurance.pas.common.pii.PiiCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AB-529 end to end against the DB2 stand-in (H2 DB2 mode) and PostgreSQL 16: chunked bulk load
 * with PII encryption, idempotent re-run, delta sync, rollback pause, reconciliation reports, and
 * injected mismatches being detected and classified.
 */
@SpringBootTest(
    properties = {
      "pas.transfer.enabled=false",
      "pas.migration.enabled=true",
      "pas.migration.source.username=sa",
      "pas.migration.chunk-size=2"
    })
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  static final String SOURCE = SourceStandIn.url("acmeins_migration_it");
  static final String ENC_KEY = SourceStandIn.randomKey();
  static final String IDX_KEY = SourceStandIn.randomKey();
  static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 12);

  @TempDir static Path reportDir;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("pas.migration.source.url", () -> SOURCE);
    registry.add("pas.migration.report-dir", () -> reportDir.toString());
    registry.add("pas.pii.encryption-key", () -> ENC_KEY);
    registry.add("pas.pii.blind-index-key", () -> IDX_KEY);
    registry.add(
        "pas.migration.triage-register",
        () -> ParityFixtures.repoRoot().resolve("parity/parallel-run/triage-register.json"));
    registry.add(
        "pas.migration.quirk-register",
        () -> ParityFixtures.repoRoot().resolve("docs/modernization/quirk-register.md"));
  }

  @BeforeAll
  static void setUp() throws Exception {
    ParityFixtures.migratePasSchema(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    SourceStandIn.seed(SOURCE);
  }

  @Autowired JobLauncher launcher;

  @Autowired
  @Qualifier("db2BulkLoadJob")
  Job bulkLoad;

  @Autowired DeltaSyncService delta;
  @Autowired SyncState syncState;
  @Autowired MigrationChecks checks;
  @Autowired MigrationStatusEndpoint endpoint;
  @Autowired SourceDatabase source;
  @Autowired JdbcTemplate target;
  @Autowired PolicyHolderPii pii;

  private JobExecution runBulkLoad() throws Exception {
    JobExecution execution =
        launcher.run(
            bulkLoad,
            new JobParametersBuilder()
                .addLocalDateTime("launchedAt", LocalDateTime.now())
                .toJobParameters());
    assertThat(execution.getStatus())
        .as(execution.getAllFailureExceptions().toString())
        .isEqualTo(BatchStatus.COMPLETED);
    return execution;
  }

  private long targetCount(String table) {
    return target.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private long sourceCount(String table) {
    return source.jdbc().queryForObject("SELECT COUNT(*) FROM ACMEINS." + table, Long.class);
  }

  @Test
  @Order(1)
  void bulkLoadCopiesEveryTableInChunksAndEncryptsPii() throws Exception {
    JobExecution execution = runBulkLoad();

    for (MigrationTable table : MigrationTable.values()) {
      assertThat(targetCount(table.targetTable()))
          .as(table.name())
          .isEqualTo(sourceCount(table.sourceTable()))
          .isPositive();
    }
    long policyChunks =
        execution.getStepExecutions().stream()
            .filter(s -> s.getStepName().equals("db2BulkLoad.POLICIES"))
            .findFirst()
            .orElseThrow()
            .getCommitCount();
    assertThat(policyChunks).as("chunk-size=2 over 3 policies").isGreaterThanOrEqualTo(2);

    Map<String, Object> holder =
        target.queryForMap(
            "SELECT tax_id_enc, tax_id_bidx, ssn_last4_enc, date_of_birth_enc, credit_score_enc"
                + " FROM pas.policy_holders WHERE cust_id = 'C000000001'");
    PiiCipher cipher = new PiiCipher(Base64.getDecoder().decode(ENC_KEY), "k1");
    BlindIndexer indexer = new BlindIndexer(Base64.getDecoder().decode(IDX_KEY));
    assertThat((String) holder.get("tax_id_enc")).doesNotContain("1234567");
    assertThat(cipher.decrypt((String) holder.get("tax_id_enc"))).isEqualTo("99-1234567");
    assertThat(cipher.decrypt((String) holder.get("ssn_last4_enc"))).isEqualTo("4321");
    assertThat(cipher.decrypt((String) holder.get("date_of_birth_enc"))).isEqualTo("1975-06-15");
    assertThat(cipher.decrypt((String) holder.get("credit_score_enc"))).isEqualTo("720");
    assertThat(holder.get("tax_id_bidx"))
        .isEqualTo(indexer.index(PolicyHolderPii.TAX_ID_PURPOSE, "99-1234567"));
    assertThat(syncState.watermark(MigrationTable.POLICIES)).isPresent();
  }

  @Test
  @Order(2)
  void bulkLoadIsIdempotentAndReconciliationPasses() throws Exception {
    runBulkLoad();
    assertThat(targetCount("pas.underwriting_decisions"))
        .isEqualTo(sourceCount("UNDERWRITING_DECISIONS"));
    // a re-run changes nothing, so no version bump; migration loads never write the outbox
    assertThat(target.queryForObject("SELECT MAX(version) FROM pas.policies", Long.class)).isZero();
    assertThat(targetCount("pas.outbox")).isZero();

    CheckReport report = checks.reconcile(BUSINESS_DATE, "it-reconcile-clean");

    assertThat(report.status()).as(report.differences().toString()).isEqualTo("PASS");
    assertThat(report.unexplained()).isZero();
    assertThat(report.differences()).isEmpty();
    Path day = reportDir.resolve("reconciliation").resolve(BUSINESS_DATE.toString());
    assertThat(day.resolve("it-reconcile-clean.json")).exists();
    assertThat(Files.readString(day.resolve("it-reconcile-clean.md"))).contains("**Status: PASS**");
    assertThat(reportDir.resolve("reconciliation/latest.json")).exists();
    @SuppressWarnings("unchecked")
    Map<String, Object> rec = (Map<String, Object>) endpoint.status().get("reconciliation");
    @SuppressWarnings("unchecked")
    Map<String, Object> latest = (Map<String, Object>) rec.get("latest");
    assertThat(latest).containsEntry("status", "PASS").containsEntry("unexplained_count", 0);
  }

  @Test
  @Order(3)
  void deltaSyncAppliesSourceChanges() throws Exception {
    SourceStandIn.execute(
        SOURCE,
        "UPDATE ACMEINS.POLICIES SET TOTAL_PREMIUM = 999.99, LAST_UPDATED = CURRENT_TIMESTAMP WHERE POLICY_NUMBER = 'POL-00000002'");
    SourceStandIn.execute(
        SOURCE,
        "UPDATE ACMEINS.COVERAGES SET PREMIUM = 321.00 WHERE POLICY_NUMBER = 'POL-00000002'");
    SourceStandIn.execute(
        SOURCE,
        "INSERT INTO ACMEINS.UNDERWRITING_DECISIONS (POLICY_NUMBER, DECISION_DATE, DECISION_CODE,"
            + " RISK_SCORE, CREATED_TIMESTAMP) VALUES ('POL-00000002', '2026-10-12', 'AP', 40,"
            + " CURRENT_TIMESTAMP)");

    DeltaSyncService.Result result = delta.sync("it-delta-1");

    assertThat(result.paused()).isFalse();
    assertThat(result.rowsApplied().get("POLICIES")).isPositive();
    assertThat(
            target.queryForObject(
                "SELECT total_premium FROM pas.policies WHERE policy_number = 'POL-00000002'",
                java.math.BigDecimal.class))
        .isEqualByComparingTo("999.99");
    assertThat(
            target.queryForObject(
                "SELECT version FROM pas.policies WHERE policy_number = 'POL-00000002'",
                Long.class))
        .isEqualTo(1L);
    assertThat(targetCount("pas.outbox")).isZero();
    assertThat(checks.reconcile(BUSINESS_DATE, "it-reconcile-after-delta").status())
        .isEqualTo("PASS");
  }

  @Test
  @Order(4)
  void pausedSyncAppliesNothingUntilResumed() throws Exception {
    syncState.setPaused(true, "rollback rehearsal", "MigrationIT");
    SourceStandIn.execute(
        SOURCE,
        "UPDATE ACMEINS.POLICIES SET POLICY_STATUS = 'CN', LAST_UPDATED = CURRENT_TIMESTAMP WHERE POLICY_NUMBER = 'POL-00000003'");

    assertThat(delta.sync("it-delta-paused").paused()).isTrue();
    CheckReport drift = checks.reconcile(BUSINESS_DATE, "it-reconcile-paused");
    assertThat(drift.status()).isEqualTo("FAIL");
    assertThat(drift.differences())
        .anySatisfy(
            d -> {
              assertThat(d.scope()).isEqualTo("POLICIES");
              assertThat(d.kind()).isEqualTo("CHANGED");
              assertThat(d.key()).isEqualTo("POL-00000003");
              assertThat(d.fields()).contains("POLICY_STATUS", "LAST_UPDATED");
              assertThat(d.classification()).isEqualTo(Classification.UNCLASSIFIED);
            });

    syncState.setPaused(false, null, "MigrationIT");
    delta.sync("it-delta-resumed");
    assertThat(checks.reconcile(BUSINESS_DATE, "it-reconcile-resumed").status()).isEqualTo("PASS");
  }

  @Test
  @Order(5)
  void injectedMismatchesAreDetectedAndClassified() throws Exception {
    target.update(
        "UPDATE pas.premiums SET total_premium = total_premium + 1 WHERE policy_number = 'POL-00000001'");
    target.update("DELETE FROM pas.endorsements WHERE policy_number = 'POL-00000002'");
    target.update(
        "UPDATE pas.policy_holders SET tax_id_enc = ? WHERE cust_id = 'C000000001'",
        new PiiCipher(Base64.getDecoder().decode(ENC_KEY), "k1").encrypt("99-7654321"));
    target.update(
        "INSERT INTO pas.policies (policy_number, policy_type, policy_status, effective_date,"
            + " expiry_date, policyholder_id, branch_code, total_premium, coverage_limit)"
            + " VALUES ('POL-99999999', 'HOM', 'AC', DATE '2026-01-01', DATE '2027-01-01',"
            + " 'C000000001', 'SPR1', 10.00, 1000.00)");

    CheckReport untriaged = checks.reconcile(BUSINESS_DATE, "it-reconcile-injected");

    assertThat(untriaged.status()).isEqualTo("FAIL");
    assertThat(untriaged.count(Classification.UNCLASSIFIED))
        .isEqualTo(untriaged.differences().size())
        .isEqualTo(untriaged.unexplained());
    assertThat(untriaged.differences())
        .extracting(d -> d.scope() + ":" + d.kind() + ":" + d.key())
        .contains(
            "PREMIUMS:CHANGED:POL-00000001|1|2025-01-01",
            "ENDORSEMENTS:COUNT_MISMATCH:*",
            "POLICIES:EXTRA_IN_TARGET:POL-99999999",
            "POLICIES:COUNT_MISMATCH:*");
    assertThat(untriaged.differences())
        .anySatisfy(
            d -> {
              assertThat(d.scope()).isEqualTo("PREMIUMS");
              assertThat(d.kind()).isEqualTo("TOTAL_MISMATCH");
            });
    Difference premium =
        untriaged.differences().stream()
            .filter(d -> d.kind().equals("CHANGED") && d.scope().equals("PREMIUMS"))
            .findFirst()
            .orElseThrow();
    Difference holder =
        untriaged.differences().stream()
            .filter(d -> d.kind().equals("CHANGED") && d.scope().equals("POLICY_HOLDERS"))
            .findFirst()
            .orElseThrow();
    assertThat(holder.fields()).containsExactly("TAX_ID");
    assertThat(holder.legacy()).isNull();
    assertThat(holder.candidate()).isNull();
    assertThat(untriaged.differences())
        .filteredOn(d -> d.scope().equals("POLICY_HOLDERS"))
        .allSatisfy(d -> assertThat(d.legacy()).isNull());
    assertThat(String.valueOf(untriaged.differences()))
        .doesNotContain("1234567")
        .doesNotContain("7654321");
    assertThat(premium.fields()).containsExactly("TOTAL_PREMIUM");
    assertThat(premium.legacy()).isEqualTo("1275.00");
    assertThat(premium.candidate()).isEqualTo("1276.00");

    TriageRegister register =
        new TriageRegister(
            List.of(
                entry(
                    "T-1",
                    "PREMIUMS",
                    "POL-00000001|*",
                    "CHANGED",
                    "TOTAL_PREMIUM",
                    Classification.BUG),
                entry(
                    "T-2",
                    "POLICIES",
                    "POL-99999999",
                    "EXTRA_IN_TARGET",
                    null,
                    Classification.DATA_ISSUE)),
            Set.of());
    CheckReport triaged =
        new ReconciliationService(source, target, register, pii, 50, Clock.systemUTC())
            .reconcile(BUSINESS_DATE, "it-reconcile-triaged");

    assertThat(triaged.count(Classification.BUG)).isEqualTo(1);
    assertThat(triaged.count(Classification.DATA_ISSUE)).isEqualTo(1);
    assertThat(triaged.unexplained())
        .isEqualTo(triaged.count(Classification.BUG) + triaged.count(Classification.UNCLASSIFIED))
        .isEqualTo(untriaged.unexplained() - 1);
    assertThat(triaged.differences())
        .filteredOn(d -> "T-1".equals(d.triageId()))
        .singleElement()
        .satisfies(d -> assertThat(d.reference()).isEqualTo("AB-529-IT"));
  }

  private static TriageRegister.Entry entry(
      String id, String scope, String key, String kind, String field, Classification c) {
    return new TriageRegister.Entry(
        id,
        "RECONCILIATION",
        scope,
        key,
        kind,
        field,
        null,
        null,
        c,
        null,
        null,
        c == Classification.BUG ? "AB-529-IT" : null,
        c == Classification.DATA_ISSUE ? "Data Migration" : null,
        "injected by MigrationIT",
        "test");
  }
}
