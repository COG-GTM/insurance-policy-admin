package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.batch.migration.bulk.MigrationRowWriter;
import com.acme.insurance.pas.batch.migration.delta.DeltaSyncService;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeltaSyncIT extends MigrationIntegrationTestSupport {
  @Test
  void coverageSweepFindsCoverageOnlyChangesWithoutPolicyTimestampUpdates() throws Exception {
    seedSource(1);
    bulkLoadAll();
    assertThat(target.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class))
        .isZero();
    DeltaSyncService service = new DeltaSyncService(source, writer, target, properties, metrics);
    service.sync();
    assertThat(target.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class))
        .isZero();
    String policyNumber = "POL000000001";
    sourceJdbc.update(
        "DELETE FROM acmeins.coverages WHERE policy_number = ? AND sequence_num = 1", policyNumber);
    sourceJdbc.update(
        """
        INSERT INTO acmeins.coverages
            (policy_number, sequence_num, coverage_type, description, coverage_limit,
             deductible, premium, effective_date, expiry_date, status)
        VALUES (?, 9, 'TEST', 'Coverage sweep', 12000, 500, 150.00,
                DATE '2025-01-01', DATE '2026-01-01', 'AC')
        """,
        policyNumber);

    service.sync();

    assertThat(
            target.queryForList(
                "SELECT sequence_num FROM pas.coverages WHERE policy_number = ? ORDER BY sequence_num",
                Integer.class,
                policyNumber))
        .containsExactly(2, 9);
    assertThat(target.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class))
        .isZero();

    sourceJdbc.update("DELETE FROM acmeins.coverages WHERE policy_number = ?", policyNumber);
    service.sync();

    assertThat(
            target.queryForList(
                "SELECT sequence_num FROM pas.coverages WHERE policy_number = ? ORDER BY sequence_num",
                Integer.class,
                policyNumber))
        .isEmpty();
    assertThat(target.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class))
        .isZero();
  }

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

  @Test
  void coverageSweepLeavesPostgresOnlyPoliciesUntouched() throws Exception {
    seedSource(1);
    bulkLoadAll();
    target.update(
        """
        INSERT INTO pas.policies
            (policy_number, policy_type, effective_date, expiry_date, policyholder_id)
        SELECT 'PGONLY000001', policy_type, effective_date, expiry_date, policyholder_id
        FROM pas.policies WHERE policy_number = 'POL000000001'
        """);
    target.update(
        """
        INSERT INTO pas.coverages
            (policy_number, sequence_num, coverage_type, description, effective_date, expiry_date)
        SELECT 'PGONLY000001', sequence_num, coverage_type, description, effective_date, expiry_date
        FROM pas.coverages WHERE policy_number = 'POL000000001' AND sequence_num = 1
        """);

    new DeltaSyncService(source, writer, target, properties, metrics).sync();

    assertThat(
            target.queryForList(
                "SELECT sequence_num FROM pas.coverages WHERE policy_number = 'PGONLY000001'",
                Integer.class))
        .containsExactly(1);
  }

  @Test
  void failedCoverageSetReplacementRollsBackTheOriginalSet() throws Exception {
    seedSource(1);
    bulkLoadAll();
    new DeltaSyncService(source, writer, target, properties, metrics).sync();
    String policyNumber = "POL000000001";
    var originalSequences =
        target.queryForList(
            "SELECT sequence_num FROM pas.coverages WHERE policy_number = ? ORDER BY sequence_num",
            Integer.class,
            policyNumber);
    sourceJdbc.update("DELETE FROM acmeins.coverages WHERE policy_number = ?", policyNumber);
    sourceJdbc.update(
        """
        INSERT INTO acmeins.coverages
            (policy_number, sequence_num, coverage_type, description, coverage_limit,
             deductible, premium, effective_date, expiry_date, status)
        VALUES
            (?, 8, 'TEST', 'Replacement one', 10000, 500, 125.00,
             DATE '2025-01-01', DATE '2026-01-01', 'AC'),
            (?, 9, 'TEST', 'Replacement two', 10000, 500, 125.00,
             DATE '2025-01-01', DATE '2026-01-01', 'AC')
        """,
        policyNumber,
        policyNumber);
    MigrationRowWriter failingWriter =
        new MigrationRowWriter(target, cipher, blindIndex, properties, metrics) {
          private int coverageWrites;

          @Override
          public void upsert(MigrationTable table, Map<String, Object> sourceRow) {
            if (table == MigrationTable.COVERAGES && coverageWrites++ == 1) {
              throw new IllegalStateException("injected coverage write failure");
            }
            super.upsert(table, sourceRow);
          }
        };

    assertThatThrownBy(
            () -> new DeltaSyncService(source, failingWriter, target, properties, metrics).sync())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("injected coverage write failure");

    assertThat(
            target.queryForList(
                "SELECT sequence_num FROM pas.coverages WHERE policy_number = ? ORDER BY sequence_num",
                Integer.class,
                policyNumber))
        .containsExactlyElementsOf(originalSequences);
  }
}
