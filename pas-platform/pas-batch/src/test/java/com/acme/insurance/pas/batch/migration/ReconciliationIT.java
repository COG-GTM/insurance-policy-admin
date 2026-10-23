package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.pii.PiiProperties;
import com.acme.insurance.pas.batch.migration.recon.ReconciliationService;
import com.acme.insurance.pas.batch.migration.reverse.ReverseSyncService;
import com.acme.insurance.pas.batch.migration.source.ReverseSyncTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class ReconciliationIT extends MigrationIntegrationTestSupport {
  @Test
  void detectsCountsTotalsAndHashesAndWritesPiiSafeReports() throws Exception {
    seedSource(2);
    bulkLoadAll();
    Path reportDir = Files.createTempDirectory("pas-reconciliation-it");
    MigrationProperties reconProperties = withReportDir(reportDir);
    PiiProperties piiProperties =
        new PiiProperties(
            new PiiProperties.Encryption("migration-it", 1, encodedKey(1)),
            new PiiProperties.BlindIndex(encodedKey(2)));
    ReconciliationService service =
        new ReconciliationService(
            source,
            target,
            reconProperties,
            piiProperties,
            cipher,
            metrics,
            new ObjectMapper().findAndRegisterModules());

    ReconciliationService.Result clean = service.reconcile();
    assertThat(clean.status()).isEqualTo("CLEAN");

    target.update("DELETE FROM pas.territory_factors WHERE factor_set = 'DB2'");
    ReconciliationService.Result countMismatch = service.reconcile();
    assertThat(countKinds(countMismatch.runId())).contains("COUNT");

    sourceJdbc.update(
        "UPDATE acmeins.policies SET total_premium = 999999.00 WHERE policy_number = ?",
        "POL000000001");
    ReconciliationService.Result totalMismatch = service.reconcile();
    assertThat(countKinds(totalMismatch.runId())).contains("TOTAL", "HASH");

    target.update(
        "UPDATE pas.policies SET policy_status = 'XX' WHERE policy_number = ?", "POL000000001");
    ReconciliationService.Result hashMismatch = service.reconcile();
    assertThat(countKinds(hashMismatch.runId())).contains("HASH");

    List<Path> reports;
    try (var files = Files.list(reportDir)) {
      reports = files.toList();
    }
    assertThat(reports).hasSize(8);
    for (Path report : reports) {
      assertThat(Files.readString(report, StandardCharsets.UTF_8))
          .doesNotContain("TAX0000001", "1980-01-01");
    }
    assertThat(
            target.queryForObject(
                "SELECT count(*) FROM pas_batch.recon_difference "
                    + "WHERE to_jsonb(recon_difference)::text LIKE '%TAX0000001%' "
                    + "OR to_jsonb(recon_difference)::text LIKE '%1980-01-01%'",
                Integer.class))
        .isZero();
  }

  @Test
  void reconcilesLatestModernDecisionAfterReverseSync() throws Exception {
    seedSource(1);
    bulkLoadAll();
    LocalDate decisionDate =
        sourceJdbc.queryForObject(
            "SELECT decision_date FROM acmeins.underwriting_decisions "
                + "WHERE policy_number = 'POL000000001'",
            LocalDate.class);
    assertThat(
            target.queryForObject(
                "SELECT source_system FROM pas.underwriting_decisions "
                    + "WHERE policy_number = 'POL000000001'",
                String.class))
        .isEqualTo("DB2");

    target.update(
        """
        INSERT INTO pas.underwriting_decisions (
            policy_number, decision_date, decision_code, risk_score, decision_reason, underwriter_id
        ) VALUES (?, ?, 'DC', 42, 'Modern decision', 'MODERN')
        """,
        "POL000000001",
        decisionDate);
    assertThat(
            target.queryForObject(
                "SELECT source_system FROM pas.underwriting_decisions "
                    + "WHERE policy_number = 'POL000000001' "
                    + "ORDER BY decision_id DESC FETCH FIRST 1 ROW ONLY",
                String.class))
        .isNull();

    ReverseSyncTarget reverseTarget = newReverseTarget();
    try {
      assertThat(enabledReverseSync(reverseTarget).drain()).isGreaterThan(0);
      ReconciliationService.Result result = newReconciliationService().reconcile();
      assertThat(result.status()).isEqualTo("CLEAN");
    } finally {
      reverseTarget.close();
    }
  }

  @Test
  void reportsUnsyncedModernDecisionAsMissingInSource() throws Exception {
    seedSource(1);
    bulkLoadAll();
    LocalDate decisionDate =
        sourceJdbc.queryForObject(
            "SELECT decision_date FROM acmeins.underwriting_decisions "
                + "WHERE policy_number = 'POL000000001'",
            LocalDate.class);

    target.update(
        """
        INSERT INTO pas.underwriting_decisions (
            policy_number, decision_date, decision_code, risk_score, decision_reason, underwriter_id
        ) VALUES (?, ?, 'DC', 42, 'Unsynced modern decision', 'MODERN')
        """,
        "POL000000001",
        decisionDate.plusDays(1));

    ReconciliationService.Result result = newReconciliationService().reconcile();
    assertThat(result.status()).isEqualTo("DIFFERENCES");
    assertThat(countKinds(result.runId())).contains("MISSING_IN_SOURCE");
  }

  private List<String> countKinds(long runId) {
    return target.queryForList(
        "SELECT DISTINCT kind FROM pas_batch.recon_difference WHERE run_id = ?",
        String.class,
        runId);
  }

  private MigrationProperties withReportDir(Path reportDir) {
    return new MigrationProperties(
        properties.enabled(),
        properties.source(),
        properties.bulk(),
        properties.deltaSync(),
        properties.reverseSync(),
        new MigrationProperties.Recon(
            false,
            properties.recon().schedule(),
            reportDir.toString(),
            properties.recon().fingerprintKey()),
        properties.parallelRun());
  }

  private ReconciliationService newReconciliationService() {
    PiiProperties piiProperties =
        new PiiProperties(
            new PiiProperties.Encryption("migration-it", 1, encodedKey(1)),
            new PiiProperties.BlindIndex(encodedKey(2)));
    return new ReconciliationService(
        source,
        target,
        properties,
        piiProperties,
        cipher,
        metrics,
        new ObjectMapper().findAndRegisterModules());
  }

  private ReverseSyncTarget newReverseTarget() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(SOURCE.getJdbcUrl());
    config.setUsername(SOURCE.getUsername());
    config.setPassword(SOURCE.getPassword());
    config.setMaximumPoolSize(1);
    config.setPoolName("reconciliation-it-reverse-sync");
    return new ReverseSyncTarget(new HikariDataSource(config));
  }

  private ReverseSyncService enabledReverseSync(ReverseSyncTarget reverseTarget) {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("reverseSyncTarget", reverseTarget);
    ObjectProvider<ReverseSyncTarget> targetProvider =
        beanFactory.getBeanProvider(ReverseSyncTarget.class);
    return new ReverseSyncService(
        target,
        source,
        targetProvider,
        withReverseSyncEnabled(),
        cipher,
        metrics,
        targetTransactionManager());
  }

  private PlatformTransactionManager targetTransactionManager() {
    return new DataSourceTransactionManager(Objects.requireNonNull(target.getDataSource()));
  }
}
