package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.pii.PiiProperties;
import com.acme.insurance.pas.batch.migration.recon.ReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
