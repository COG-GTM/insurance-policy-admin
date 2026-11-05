package com.acme.insurance.pas.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.UnderwritingDecision;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V50-V52 (AB-525): outbox, append-only audit, default coverages and the decision writes. */
@Testcontainers
class NewBusinessPersistenceIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static JdbcClient jdbc;

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
  }

  static Policy policy(String number, String branch, String status, String limit) {
    return new Policy(
        number,
        "HOM",
        status,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1),
        "C000000001",
        "AG0001",
        branch,
        new BigDecimal("1000.00"),
        BigDecimal.ZERO,
        new BigDecimal(limit),
        LocalDate.of(2026, 1, 1),
        0,
        "PN",
        0,
        "N",
        "N",
        null,
        "POLNEW");
  }

  @Test
  void defaultCoveragesAreTheCodesPolnewHardcodes() {
    DefaultCoverageJdbcRepository repo = new DefaultCoverageJdbcRepository(jdbc);
    assertThat(repo.forPolicyType("AUT"))
        .containsExactly(
            new DefaultCoverage("AUT", 1, "AUTL", "Auto Liability"),
            new DefaultCoverage("AUT", 2, "AUTP", "Auto Physical Damage"));
    assertThat(repo.forPolicyType("HOM"))
        .extracting(DefaultCoverage::coverageType)
        .containsExactly("PROP", "LIAB");
    assertThat(repo.forPolicyType("COM"))
        .extracting(DefaultCoverage::description)
        .containsExactly("Commercial Property", "General Liability", "Workers Compensation");
    assertThat(repo.forPolicyType("LIF")).isEmpty();
    assertThat(repo.forPolicyType("HLT")).isEmpty();
    assertThat(repo.forPolicyType("CGL")).isEmpty();
  }

  @Test
  void policySequenceStartsAtTheDb2Value() {
    long first = new PolicySequence(jdbc, DatabaseVendor.POSTGRESQL).next();
    assertThat(first).isGreaterThanOrEqualTo(1_000_000L);
    assertThat(DatabaseVendor.fromProductName("PostgreSQL")).isEqualTo(DatabaseVendor.POSTGRESQL);
    assertThat(DatabaseVendor.fromProductName("DB2")).isEqualTo(DatabaseVendor.DB2);
    assertThatThrownBy(() -> DatabaseVendor.fromProductName("Oracle"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void outboxRelayLifecycle() {
    EventOutboxJdbcRepository outbox = new EventOutboxJdbcRepository(jdbc);
    outbox.append(
        OutboxRow.pending(
            "11111111-1111-1111-1111-111111111111",
            "pas.policy.created",
            "pas.policy.created",
            "POL-OUTBOX01",
            "{\"a\":1}"));
    outbox.append(
        OutboxRow.pending(
            "22222222-2222-2222-2222-222222222222",
            "pas.underwriting.requested",
            "pas.underwriting.requested",
            "POL-OUTBOX01",
            "{\"b\":2}"));
    List<OutboxRow> pending = outbox.pending(10);
    assertThat(pending)
        .extracting(OutboxRow::eventType)
        .containsSubsequence("pas.policy.created", "pas.underwriting.requested");
    OutboxRow first = outbox.findByMessageKey("POL-OUTBOX01").get(0);
    outbox.markFailed(first.outboxSeq(), "broker down");
    assertThat(outbox.findByMessageKey("POL-OUTBOX01").get(0).lastError()).isEqualTo("broker down");
    outbox.markPublished(first.outboxSeq(), LocalDateTime.now().minusDays(8));
    OutboxRow published = outbox.findByMessageKey("POL-OUTBOX01").get(0);
    assertThat(published.publishedAt()).isNotNull();
    assertThat(published.attempts()).isEqualTo(2);
    assertThat(published.lastError()).isNull();
    assertThat(outbox.pending(10)).extracting(OutboxRow::eventId).doesNotContain(first.eventId());
    assertThat(outbox.deletePublishedBefore(LocalDateTime.now().minusDays(7))).isEqualTo(1);
    assertThat(outbox.findByMessageKey("POL-OUTBOX01")).hasSize(1);
    assertThatThrownBy(
            () ->
                outbox.append(
                    OutboxRow.pending(
                        "22222222-2222-2222-2222-222222222222", "x", "x", "POL-OUTBOX01", "{}")))
        .hasMessageContaining("uq_event_outbox_event");
  }

  @Test
  void auditIsAppendOnly() {
    PolicyAuditJdbcRepository audit = new PolicyAuditJdbcRepository(jdbc);
    audit.append(
        new PolicyAuditEntry(
            null,
            null,
            "POLICY_CREATED",
            "POL-AUDIT001",
            "user@example.test",
            "broker-portal",
            "POLNEW",
            "corr",
            "{\"after\":{}}"));
    List<PolicyAuditEntry> rows = audit.findByPolicy("POL-AUDIT001");
    assertThat(rows)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.principal()).isEqualTo("user@example.test");
              assertThat(e.legacyUser()).isEqualTo("POLNEW");
              assertThat(e.occurredAt()).isNotNull();
            });
    assertThatThrownBy(() -> jdbc.sql("UPDATE pasapp.policy_audit SET principal = 'x'").update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM pasapp.policy_audit").update())
        .hasMessageContaining("append-only");
  }

  @Test
  void decisionWritesAndOverride() {
    PolicyJdbcRepository policies = new PolicyJdbcRepository(jdbc);
    UnderwritingDecisionJdbcRepository decisions = new UnderwritingDecisionJdbcRepository(jdbc);
    policies.insert(policy("POL-UWD00001", "B525", "PN", "250000.00"));
    LocalDate today = LocalDate.of(2026, 1, 1);
    decisions.insert(
        new UnderwritingDecision(
            null,
            "POL-UWD00001",
            today,
            "RS",
            450,
            "REFERRED TO SENIOR UNDERWRITER",
            "SYSTEM",
            null,
            null,
            null));
    // Same policy, same day: kept on PostgreSQL (Q-18 fix).
    decisions.insert(
        new UnderwritingDecision(
            null,
            "POL-UWD00001",
            today,
            "RM",
            650,
            "REFERRED TO UW MANAGER",
            "SYSTEM",
            null,
            null,
            null));
    assertThat(policies.updateUnderwriting("POL-UWD00001", "RM", 650, "UNDWRT")).isEqualTo(1);
    Policy updated = policies.findPolicy("POL-UWD00001").orElseThrow();
    assertThat(updated.uwStatusCode()).isEqualTo("RM");
    assertThat(updated.riskScore()).isEqualTo(650);
    assertThat(updated.updatedBy()).isEqualTo("UNDWRT");

    UnderwritingDecision latest = decisions.findLatest("POL-UWD00001").orElseThrow();
    assertThat(latest.decisionCode()).isEqualTo("RM");
    UnderwritingDecision after =
        new UnderwritingDecision(
            null,
            latest.policyNumber(),
            latest.decisionDate(),
            "AP",
            latest.riskScore(),
            latest.decisionReason(),
            latest.underwriterId(),
            "Inspected",
            "UW00042",
            latest.createdTimestamp());
    assertThat(decisions.applyOverride(latest, after)).isEqualTo(1);
    assertThat(decisions.applyOverride(latest, after)).as("stale override").isZero();
    assertThat(decisions.findByPolicy("POL-UWD00001"))
        .extracting(UnderwritingDecision::decisionCode)
        .containsExactly("RS", "AP");
    assertThat(decisions.findLatest("POL-UWD00001").orElseThrow().overrideBy())
        .isEqualTo("UW00042");

    UnderwritingDecision overridden = decisions.findLatest("POL-UWD00001").orElseThrow();
    UnderwritingDecision again = withOverride(overridden, "AP", "Second look", "UW00043");
    UnderwritingDecision racing = withOverride(overridden, "AP", "Racing look", "UW00044");
    assertThat(decisions.applyOverride(overridden, again)).isEqualTo(1);
    assertThat(decisions.applyOverride(overridden, racing))
        .as("same-code override based on a stale row")
        .isZero();
    assertThat(decisions.findLatest("POL-UWD00001").orElseThrow().overrideReason())
        .isEqualTo("Second look");
  }

  private static UnderwritingDecision withOverride(
      UnderwritingDecision d, String code, String reason, String by) {
    return new UnderwritingDecision(
        null,
        d.policyNumber(),
        d.decisionDate(),
        code,
        d.riskScore(),
        d.decisionReason(),
        d.underwriterId(),
        reason,
        by,
        d.createdTimestamp());
  }

  @Test
  void branchAccumulationCountsActivePoliciesOnly() {
    PolicyJdbcRepository policies = new PolicyJdbcRepository(jdbc);
    policies.insert(policy("POL-ACC00001", "BACC", "AC", "300000000.00"));
    policies.insert(policy("POL-ACC00002", "BACC", "AC", "200000000.01"));
    policies.insert(policy("POL-ACC00003", "BACC", "PN", "900000000.00"));
    assertThat(policies.activeCoverageLimitForBranch("BACC")).isEqualByComparingTo("500000000.01");
    assertThat(policies.activeCoverageLimitForBranch("NONE")).isEqualByComparingTo("0");
  }
}
