package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.delta.DeltaSyncService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DeltaSyncIT extends MigrationIntegrationTestSupport {
  @Test
  void refreshesCoverageAsASetWhenPolicyWatermarkAdvances() throws Exception {
    seedSource(2);
    bulkLoadAll();
    DeltaSyncService service = new DeltaSyncService(source, writer, target, properties, metrics);
    service.sync();
    Timestamp watermark =
        target.queryForObject(
            "SELECT watermark_value FROM pas_batch.sync_watermark "
                + "WHERE table_name = 'POLICIES' AND direction = 'FORWARD'",
            Timestamp.class);
    String policyNumber = "POL000000001";
    sourceJdbc.update(
        "DELETE FROM acmeins.coverages WHERE policy_number = ? AND sequence_num = 1", policyNumber);
    sourceJdbc.update(
        """
        INSERT INTO acmeins.coverages
            (policy_number, sequence_num, coverage_type, description, coverage_limit,
             deductible, premium, effective_date, expiry_date, status)
        VALUES (?, 9, 'TEST', 'Delta coverage', 10000, 500, 125.00,
                DATE '2025-01-01', DATE '2026-01-01', 'AC')
        """,
        policyNumber);
    sourceJdbc.update(
        "UPDATE acmeins.policies SET last_updated = ? WHERE policy_number = ?",
        watermark.toLocalDateTime().minusMinutes(1),
        policyNumber);

    service.sync();

    assertThat(
            target.queryForList(
                "SELECT sequence_num FROM pas.coverages WHERE policy_number = ? ORDER BY sequence_num",
                Integer.class,
                policyNumber))
        .contains(9)
        .doesNotContain(1);
    assertThat(
            target.queryForObject(
                "SELECT count(*) FROM pas_batch.sync_watermark "
                    + "WHERE table_name = 'POLICIES' AND direction = 'FORWARD'",
                Integer.class))
        .isEqualTo(1);
    LocalDateTime retainedWatermark =
        target.queryForObject(
            "SELECT watermark_value FROM pas_batch.sync_watermark "
                + "WHERE table_name = 'POLICIES' AND direction = 'FORWARD'",
            LocalDateTime.class);
    assertThat(retainedWatermark).isEqualTo(watermark.toLocalDateTime());
    assertThat(metrics.find("pas.sync.lag.seconds").tag("table", "POLICIES").gauge()).isNotNull();
  }
}
