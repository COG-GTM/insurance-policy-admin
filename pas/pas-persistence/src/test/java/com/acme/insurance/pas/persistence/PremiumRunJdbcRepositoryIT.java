package com.acme.insurance.pas.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.Premium;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V30 (AB-523): partition keys, the idempotent PREMIUMS upsert and per-policy run outcomes. */
@Testcontainers
class PremiumRunJdbcRepositoryIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static final LocalDate TERM = LocalDate.of(2025, 1, 1);

  static JdbcTemplate jdbc;
  static PremiumRunJdbcRepository repository;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/postgresql")
        .load()
        .migrate();
    jdbc = new JdbcTemplate(dataSource);
    repository = new PremiumRunJdbcRepository(jdbc);
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM pasapp.premium_run_item");
    jdbc.update("DELETE FROM acmeins.premiums");
    jdbc.update("DELETE FROM acmeins.policies");
  }

  @Test
  void partitionStartsSplitTheActiveBookIntoContiguousRanges() {
    for (int i = 1; i <= 10; i++) {
      policy("POL-%08d".formatted(i), i == 4 ? "CN" : "AC");
    }
    assertThat(repository.countActivePolicies()).isEqualTo(9);
    assertThat(repository.partitionStarts(3))
        .containsExactly("POL-00000001", "POL-00000005", "POL-00000008");
    assertThat(repository.partitionStarts(20)).hasSize(9);
  }

  @Test
  void anEmptyBookHasNoPartitions() {
    assertThat(repository.partitionStarts(4)).isEmpty();
  }

  @Test
  void upsertInsertsMissingRowsAndReplacesExistingOnes() {
    policy("POL-00000001", "AC");
    policy("POL-00000002", "AC");
    repository.upsertPremiums(List.of(premium("POL-00000001", "904.75")));
    repository.upsertPremiums(
        List.of(premium("POL-00000001", "439.00"), premium("POL-00000002", "1267.25")));

    assertThat(
            jdbc.queryForList(
                "SELECT policy_number, total_premium FROM acmeins.premiums ORDER BY policy_number"))
        .extracting(r -> r.get("policy_number") + "=" + r.get("total_premium"))
        .containsExactly("POL-00000001=439.00", "POL-00000002=1267.25");
  }

  @Test
  void outcomesAreTotalledAndStreamedInPolicyOrder() {
    LocalDate day = LocalDate.of(2025, 3, 1);
    repository.recordOutcomes(
        7L,
        day,
        List.of(
            PremiumRunItem.updated("POL-00000003", new BigDecimal("904.75")),
            PremiumRunItem.error(
                "POL-00000001", "DataIntegrityViolationException", "SQLSTATE 22007")));
    repository.recordOutcomes(
        7L, day, List.of(PremiumRunItem.updated("POL-00000001", new BigDecimal("439.00"))));
    repository.recordOutcomes(
        8L, day, List.of(PremiumRunItem.updated("POL-00000009", new BigDecimal("1.00"))));

    assertThat(repository.totals(7L)).isEqualTo(new PremiumRunTotals(2, 2, 0));
    List<String> streamed = new ArrayList<>();
    repository.streamOutcomes(7L, i -> streamed.add(i.policyNumber() + ":" + i.status()));
    assertThat(streamed).containsExactly("POL-00000001:UPDATED", "POL-00000003:UPDATED");
  }

  private static void policy(String number, String status) {
    jdbc.update(
        """
        INSERT INTO acmeins.policies (policy_number, policy_type, policy_status, effective_date,
          expiry_date, policyholder_id, total_premium, deductible, coverage_limit)
        VALUES (?, 'AUT', ?, DATE '2025-01-01', DATE '2026-01-01', 'H000000001', 1000.00, 500.00,
          250000.00)
        """,
        number,
        status);
  }

  private static Premium premium(String policy, String total) {
    BigDecimal one = BigDecimal.ONE.setScale(4);
    BigDecimal zero = new BigDecimal("0.00");
    return new Premium(
        policy,
        1,
        TERM,
        TERM.plusYears(1),
        new BigDecimal("850.00"),
        one,
        one,
        one,
        one,
        zero,
        new BigDecimal("25.00"),
        zero,
        new BigDecimal(total),
        "AN",
        new BigDecimal(total),
        LocalDate.of(2025, 3, 1),
        "PREMBAT");
  }
}
