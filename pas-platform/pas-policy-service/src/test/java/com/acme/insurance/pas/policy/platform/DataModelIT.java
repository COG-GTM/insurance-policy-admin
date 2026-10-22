package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
import com.acme.insurance.pas.domain.CustomerType;
import com.acme.insurance.pas.domain.Endorsement;
import com.acme.insurance.pas.domain.InstallmentCode;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.Premium;
import com.acme.insurance.pas.domain.RiskTier;
import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.acme.insurance.pas.domain.UnderwritingDecisionCode;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import com.acme.insurance.pas.policy.repository.CoverageRepository;
import com.acme.insurance.pas.policy.repository.EndorsementRepository;
import com.acme.insurance.pas.policy.repository.PolicyHolderRepository;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import com.acme.insurance.pas.policy.repository.PremiumRepository;
import com.acme.insurance.pas.policy.repository.RatingFactorRepository;
import com.acme.insurance.pas.policy.repository.UnderwritingDecisionRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("it")
class DataModelIT {
  private static final String POLICY_NUMBER = "POL000100000";
  private static final LocalDate EFFECTIVE_DATE = LocalDate.of(2024, 1, 1);
  private static final OffsetDateTime UPDATED =
      OffsetDateTime.of(2024, 1, 1, 12, 30, 0, 0, ZoneOffset.UTC);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PolicyRepository policies;
  @Autowired private CoverageRepository coverages;
  @Autowired private PremiumRepository premiums;
  @Autowired private EndorsementRepository endorsements;
  @Autowired private UnderwritingDecisionRepository decisions;
  @Autowired private PolicyHolderRepository holders;
  @Autowired private RatingFactorRepository factors;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void repositoriesRoundTripEveryCoreRecordAndResolveActiveFactors() {
    Policy policy = policy();
    Coverage coverage = coverage();
    Premium premium = premium();
    Endorsement endorsement = endorsement();
    UnderwritingDecision decision = decision();
    PolicyHolder holder = holder();
    PolicyHolder duplicateTaxIdHolder = holder("CUST000004", "987654320");

    policies.insert(policy);
    coverages.insert(coverage);
    premiums.insert(premium);
    endorsements.insert(endorsement);
    decisions.insert(decision);
    holders.insert(holder);
    holders.insert(duplicateTaxIdHolder);

    assertThat(policies.findByPolicyNumber(POLICY_NUMBER)).contains(policy);
    assertThat(coverages.findByKey(POLICY_NUMBER, 1)).contains(coverage);
    assertThat(premiums.findByKey(POLICY_NUMBER, 1, EFFECTIVE_DATE)).contains(premium);
    assertThat(endorsements.findByKey(POLICY_NUMBER, 1)).contains(endorsement);
    assertThat(decisions.findByKey(POLICY_NUMBER, EFFECTIVE_DATE)).contains(decision);
    assertThat(holders.findByCustId(holder.custId())).contains(holder);
    assertThat(holders.findByTaxId("987-654-320")).containsExactly(holder, duplicateTaxIdHolder);
    assertThat(coverages.findByPolicyNumber(POLICY_NUMBER)).containsExactly(coverage);
    assertThat(endorsements.findByPolicyNumber(POLICY_NUMBER)).containsExactly(endorsement);
    assertThat(decisions.findByPolicyNumber(POLICY_NUMBER)).containsExactly(decision);

    jdbc.update(
        "INSERT INTO pas.territory_factors (factor_set, territory_code, effective_date, rating_factor) VALUES "
            + "('LEGACY', '*', DATE '2023-01-01', 1.1000), "
            + "('LEGACY', 'NY0001', DATE '2020-01-01', 1.2500), "
            + "('LEGACY', 'NY0001', DATE '2022-01-01', 1.4000)");
    jdbc.update(
        "INSERT INTO pas.class_factors (factor_set, policy_type, class_code, effective_date, rating_factor) VALUES "
            + "('LEGACY', '*', 'PPA01', DATE '2023-01-01', 1.2000), "
            + "('LEGACY', 'AUT', 'PPA01', DATE '2020-01-01', 1.0500), "
            + "('LEGACY', 'AUT', 'PPA01', DATE '2022-01-01', 1.1000)");

    assertThat(factors.resolveTerritoryFactor("NY0001", LocalDate.of(2024, 1, 1)))
        .contains(new BigDecimal("1.4000"));
    assertThat(factors.resolveTerritoryFactor("NY0001", LocalDate.of(2021, 1, 1)))
        .contains(new BigDecimal("1.2500"));
    assertThat(factors.resolveClassFactor("AUT", "PPA01", LocalDate.of(2024, 1, 1)))
        .contains(new BigDecimal("1.1000"));
    assertThat(factors.resolveTerritoryFactor("ZZ9999", LocalDate.of(2024, 1, 1)))
        .contains(new BigDecimal("1.1000"));
  }

  @Test
  void schemaUsesTargetTypesOmitsRegulatedCleartextAndEnforcesKeysAndSequenceLimit() {
    assertColumn("policies", "policy_number", "character", 12, null, null);
    assertColumn("policies", "total_premium", "numeric", null, 11, 2);
    assertColumn("policies", "last_updated", "timestamp with time zone", null, null, null);
    assertColumn("coverages", "sequence_num", "smallint", null, 16, 0);
    assertColumn("premiums", "territory_factor", "numeric", null, 7, 4);
    assertColumn("endorsements", "processed_date", "timestamp with time zone", null, null, null);
    assertColumn("underwriting_decisions", "decision_date", "date", null, null, null);
    assertColumn("policy_holders", "tax_id_enc", "bytea", null, null, null);
    assertColumn("territory_factors", "rating_factor", "numeric", null, 7, 4);

    List<String> regulatedCleartextColumns =
        jdbc.queryForList(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'pas' AND table_name = 'policy_holders'
              AND column_name IN ('date_of_birth', 'ssn_last4', 'tax_id', 'credit_score')
            """,
            String.class);
    assertThat(regulatedCleartextColumns).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'pas' "
                    + "AND indexname = 'ix_ph_tax_id_bidx'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT max_value FROM pg_sequences WHERE schemaname = 'pas' "
                    + "AND sequencename = 'policy_seq'",
                Long.class))
        .isEqualTo((long) Integer.MAX_VALUE);
    assertThat(jdbc.queryForObject("SELECT nextval('pas.policy_seq')", Long.class))
        .isEqualTo(1_000_000L);

    policies.insert(policy("POL000200000"));
    assertThatThrownBy(() -> policies.insert(policy("POL000200000")))
        .hasRootCauseInstanceOf(SQLException.class);
    jdbc.update(
        """
        INSERT INTO pas.policies
            (policy_number, policy_type, effective_date, expiry_date, policyholder_id)
        VALUES ('POL000100001', 'AUT', DATE '2024-01-01', DATE '2025-01-01', 'CUST000002')
        """);
    assertThat(
            jdbc.queryForMap(
                "SELECT policy_status, total_premium, rtrim(updated_by) AS updated_by, last_updated "
                    + "FROM pas.policies WHERE policy_number = 'POL000100001'"))
        .containsEntry("policy_status", "PN")
        .containsEntry("total_premium", new BigDecimal("0.00"))
        .containsEntry("updated_by", "SYSTEM");

    holders.insert(holder("CUST000002", "987654321"));
    jdbc.update("INSERT INTO pas.policy_holders (cust_id) VALUES ('CUST000003')");
    assertThat(
            jdbc.queryForMap(
                "SELECT country_code, risk_tier, gdpr_consent FROM pas.policy_holders "
                    + "WHERE cust_id = 'CUST000002'"))
        .containsEntry("country_code", "USA")
        .containsEntry("risk_tier", "S")
        .containsEntry("gdpr_consent", "N");
    byte[] ciphertext =
        jdbc.queryForObject(
            "SELECT tax_id_enc FROM pas.policy_holders WHERE cust_id = 'CUST000002'", byte[].class);
    assertThat(ciphertext).isNotEqualTo("987654321".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pas.policy_holders SET tax_id_bidx = ? WHERE cust_id = ?",
                    new byte[31],
                    "CUST000002"))
        .hasRootCauseInstanceOf(SQLException.class);
  }

  @Test
  void policySequenceStopsAtTheIntegerMaximumWithoutCycling() {
    try {
      jdbc.queryForObject("SELECT setval('pas.policy_seq', 2147483647, true)", Long.class);
      assertThatThrownBy(() -> jdbc.queryForObject("SELECT nextval('pas.policy_seq')", Long.class))
          .hasMessageContaining("maximum value");
    } finally {
      jdbc.queryForObject("SELECT setval('pas.policy_seq', 999999, false)", Long.class);
    }
  }

  private void assertColumn(
      String table,
      String column,
      String dataType,
      Integer characterLength,
      Integer precision,
      Integer scale) {
    var row =
        jdbc.queryForMap(
            "SELECT data_type, character_maximum_length, numeric_precision, numeric_scale "
                + "FROM information_schema.columns "
                + "WHERE table_schema = 'pas' AND table_name = ? AND column_name = ?",
            table,
            column);
    assertThat(row.get("data_type")).isEqualTo(dataType);
    assertThat(number(row.get("character_maximum_length"))).isEqualTo(characterLength);
    assertThat(number(row.get("numeric_precision"))).isEqualTo(precision);
    assertThat(number(row.get("numeric_scale"))).isEqualTo(scale);
  }

  private static Integer number(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  private static Policy policy() {
    return policy(POLICY_NUMBER);
  }

  private static Policy policy(String policyNumber) {
    return new Policy(
        policyNumber,
        Coded.of(PolicyType.AUTO),
        Coded.of(PolicyStatus.PENDING),
        EFFECTIVE_DATE,
        EFFECTIVE_DATE.plusYears(1),
        "CUST000001",
        "AGT001",
        "NY01",
        new BigDecimal("1234.56"),
        new BigDecimal("250.00"),
        new BigDecimal("50000.00"),
        EFFECTIVE_DATE,
        1,
        Coded.of(UnderwritingStatus.APPROVED),
        650,
        Coded.of(YesNo.YES),
        Coded.of(YesNo.NO),
        UPDATED,
        "SYSTEM");
  }

  private static Coverage coverage() {
    return new Coverage(
        POLICY_NUMBER,
        1,
        Coded.of(CoverageType.PROPERTY),
        "Property",
        new BigDecimal("50000.00"),
        new BigDecimal("250.00"),
        new BigDecimal("1234.56"),
        EFFECTIVE_DATE,
        EFFECTIVE_DATE.plusYears(1),
        Coded.of(CoverageStatus.ACTIVE),
        100,
        "NY0001",
        "PPA01");
  }

  private static Premium premium() {
    return new Premium(
        POLICY_NUMBER,
        1,
        EFFECTIVE_DATE,
        EFFECTIVE_DATE.plusYears(1),
        new BigDecimal("1234.5600"),
        new BigDecimal("1.0000"),
        new BigDecimal("1.0000"),
        new BigDecimal("1.0000"),
        new BigDecimal("1.0000"),
        new BigDecimal("0.00"),
        new BigDecimal("10.00"),
        new BigDecimal("2.00"),
        new BigDecimal("1246.56"),
        Coded.of(InstallmentCode.ANNUAL),
        new BigDecimal("1246.56"),
        EFFECTIVE_DATE,
        "SYSTEM");
  }

  private static Endorsement endorsement() {
    return new Endorsement(
        POLICY_NUMBER,
        1,
        "ADD",
        EFFECTIVE_DATE,
        "Add coverage",
        new BigDecimal("12.34"),
        UPDATED,
        "SYSTEM");
  }

  private static UnderwritingDecision decision() {
    return new UnderwritingDecision(
        POLICY_NUMBER,
        EFFECTIVE_DATE,
        Coded.of(UnderwritingDecisionCode.APPROVED),
        650,
        "Approved",
        "UW000001",
        null,
        null,
        UPDATED);
  }

  private static PolicyHolder holder() {
    return holder("CUST000001", "987654320", null, null);
  }

  private static PolicyHolder holder(String custId, String taxId) {
    return holder(custId, taxId, Coded.of(RiskTier.STANDARD), Coded.of(YesNo.NO));
  }

  private static PolicyHolder holder(
      String custId, String taxId, Coded<RiskTier> riskTier, Coded<YesNo> gdprConsent) {
    return new PolicyHolder(
        custId,
        Coded.of(CustomerType.INDIVIDUAL),
        "Example",
        "Jane",
        null,
        null,
        "1 Main Street",
        null,
        "Albany",
        "NY",
        "10001",
        "USA",
        "5551234567",
        "jane@example.test",
        LocalDate.of(1980, 4, 17),
        "4831",
        taxId,
        713,
        riskTier,
        gdprConsent,
        EFFECTIVE_DATE,
        UPDATED);
  }
}
