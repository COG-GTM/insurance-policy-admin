package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.reconcile.Canonical;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalTest {

  @Test
  void normalisesTypesTheSameWayForDb2AndPostgres() {
    assertThat(Canonical.value("total_premium", new BigDecimal("12.5"))).isEqualTo("12.50");
    assertThat(Canonical.value("base_rate", new BigDecimal("1250"))).isEqualTo("1250.0000");
    assertThat(Canonical.value("renewal_count", (short) 3)).isEqualTo("3");
    assertThat(Canonical.value("renewal_count", 3L)).isEqualTo("3");
    assertThat(Canonical.value("agent_code", "AG01  ")).isEqualTo("AG01");
    assertThat(Canonical.value("description", "a|b\\c")).isEqualTo("a\\|b\\\\c");
    assertThat(Canonical.value("x", null)).isEqualTo("\\N");
    assertThat(Canonical.value("effective_date", Date.valueOf("2026-02-28")))
        .isEqualTo(Canonical.value("effective_date", LocalDate.of(2026, 2, 28)))
        .isEqualTo("2026-02-28");
    assertThat(Canonical.value("last_updated", Timestamp.valueOf("2026-10-12 10:15:00.5")))
        .isEqualTo(
            Canonical.value("last_updated", LocalDateTime.of(2026, 10, 12, 10, 15, 0, 500_000_000)))
        .isEqualTo("2026-10-12T10:15:00.500000");
  }

  @Test
  void recordHashIsStableAcrossDriverRepresentations() {
    List<String> columns = MigrationTable.POLICIES.columns();
    Map<String, Object> db2 = new HashMap<>();
    db2.put("policy_number", "POL-00000001");
    db2.put("total_premium", new BigDecimal("1200.00"));
    db2.put("renewal_count", (short) 1);
    db2.put("agent_code", "AG1001");
    Map<String, Object> pg = new HashMap<>();
    pg.put("policy_number", "POL-00000001");
    pg.put("total_premium", new BigDecimal("1200.0"));
    pg.put("renewal_count", 1);
    pg.put("agent_code", "AG1001");
    assertThat(Canonical.sha256(Canonical.record(columns, db2)))
        .isEqualTo(Canonical.sha256(Canonical.record(columns, pg)))
        .hasSize(64);
    pg.put("total_premium", new BigDecimal("1200.01"));
    assertThat(Canonical.sha256(Canonical.record(columns, db2)))
        .isNotEqualTo(Canonical.sha256(Canonical.record(columns, pg)));
  }
}
