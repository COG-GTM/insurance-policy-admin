package com.acme.insurance.pas.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Endorsement;
import com.acme.insurance.pas.domain.Policy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** AB-526 writes: renewal and premium updates, endorsements and the append-only audit (V60). */
@Testcontainers
class PolicyChangeRepositoryIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static JdbcClient jdbc;
  static PolicyJdbcRepository policies;
  static EndorsementJdbcRepository endorsements;
  static PolicyChangeAuditJdbcRepository audit;

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
    jdbc = JdbcClient.create(dataSource);
    policies = new PolicyJdbcRepository(jdbc);
    endorsements = new EndorsementJdbcRepository(jdbc);
    audit = new PolicyChangeAuditJdbcRepository(jdbc);
  }

  private static Policy policy(String number) {
    return new Policy(
        number,
        "AUT",
        "AC",
        LocalDate.of(2024, 2, 29),
        LocalDate.of(2025, 2, 28),
        "C000000001",
        "AG1001",
        "CHI1",
        new BigDecimal("1000.00"),
        null,
        null,
        LocalDate.of(2020, 2, 29),
        2,
        "AP",
        null,
        "N",
        "N",
        LocalDateTime.of(2024, 2, 29, 9, 0),
        "TEST");
  }

  private static Coverage coverage(String number, int seq, String status) {
    return new Coverage(
        number,
        seq,
        "BI",
        null,
        null,
        null,
        new BigDecimal("10.00"),
        LocalDate.of(2024, 2, 29),
        LocalDate.of(2025, 2, 28),
        status,
        null,
        null,
        null);
  }

  @Test
  void renewalUpdatesThePolicyAndRedatesOnlyActiveCoverages() {
    policies.insert(policy("POL-52600001"));
    policies.insert(coverage("POL-52600001", 1, "AC"));
    policies.insert(coverage("POL-52600001", 2, "CN"));
    Policy current = policies.findPolicy("POL-52600001").orElseThrow();
    LocalDateTime now = LocalDateTime.of(2025, 2, 20, 10, 0);
    Policy renewed =
        new Policy(
            current.policyNumber(),
            current.policyTypeCode(),
            "AC",
            LocalDate.of(2025, 2, 28),
            LocalDate.of(2026, 2, 28),
            current.policyholderId(),
            current.agentCode(),
            current.branchCode(),
            new BigDecimal("1050.00"),
            current.deductible(),
            current.coverageLimit(),
            current.inceptionDate(),
            3,
            "PN",
            current.riskScore(),
            current.webIndicator(),
            current.apiFlag(),
            now,
            "POLRNW");

    assertThat(policies.updateRenewal(renewed)).isEqualTo(1);
    assertThat(
            policies.redateActiveCoverages(
                "POL-52600001", LocalDate.of(2025, 2, 28), LocalDate.of(2026, 2, 28)))
        .isEqualTo(1);

    assertThat(policies.findPolicy("POL-52600001").orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(renewed);
    List<Coverage> coverages = policies.findCoverages("POL-52600001");
    assertThat(coverages.get(0).expiryDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(coverages.get(1).expiryDate()).isEqualTo(LocalDate.of(2025, 2, 28));
  }

  @Test
  void endorsementsGetTheNextSequenceAndAPrimaryKeyStopsDuplicates() {
    policies.insert(policy("POL-52600002"));
    assertThat(endorsements.nextSequence("POL-52600002")).isEqualTo(1);
    Endorsement first =
        new Endorsement(
            "POL-52600002",
            1,
            "LCH",
            LocalDate.of(2024, 6, 1),
            "limit up",
            new BigDecimal("12.34"),
            LocalDateTime.of(2024, 6, 1, 9, 30),
            "POLEND");
    endorsements.insert(first);
    policies.updateTotalPremium(
        "POL-52600002", new BigDecimal("1012.34"), LocalDateTime.of(2024, 6, 1, 9, 30), "POLEND");

    assertThat(endorsements.nextSequence("POL-52600002")).isEqualTo(2);
    assertThat(endorsements.findByPolicy("POL-52600002")).containsExactly(first);
    assertThat(policies.findPolicy("POL-52600002").orElseThrow().totalPremium())
        .isEqualByComparingTo("1012.34");
    assertThatThrownBy(() -> endorsements.insert(first)).isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void auditRowsRoundTripAndCannotBeChangedOrDeleted() {
    PolicyChangeAudit row =
        new PolicyChangeAudit(
            "POL-52600003",
            PolicyChangeAudit.Operation.ENDORSEMENT,
            "POLEND",
            1,
            "CAD",
            "user@example.invalid",
            "client-1",
            "corr-1",
            "AC",
            "AC",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2025, 1, 1),
            new BigDecimal("100.00"),
            new BigDecimal("0.00"),
            new BigDecimal("100.00"),
            0,
            LocalDateTime.of(2024, 6, 1, 9, 30, 0, 123456000));
    audit.insert(row);

    assertThat(audit.findByPolicy("POL-52600003")).containsExactly(row);
    assertThatThrownBy(
            () -> jdbc.sql("UPDATE pasapp.policy_change_audit SET principal = 'x'").update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM pasapp.policy_change_audit").update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.sql("TRUNCATE pasapp.policy_change_audit").update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO pasapp.policy_change_audit (policy_number, operation,"
                            + " legacy_program, principal, occurred_at)"
                            + " VALUES ('POL-52600003', 'ENDORSEMENT', 'POLEND', 'u', now())")
                    .update())
        .as("an endorsement audit row needs its sequence and type")
        .isInstanceOf(DataAccessException.class);
  }
}
