package com.acme.insurance.pas.policy.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.acme.insurance.pas.common.domain.CoverageRecord;
import com.acme.insurance.pas.common.domain.CustomerRecord;
import com.acme.insurance.pas.common.domain.PolicyNumbers;
import com.acme.insurance.pas.common.domain.PolicyRecord;
import com.acme.insurance.pas.common.domain.PremiumRecord;
import com.acme.insurance.pas.common.pii.BlindIndexer;
import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AB-521 acceptance: Flyway V1..V5 (V5 = AB-522 rating tables) on an empty Postgres, Hibernate
 * {@code ddl-auto=validate} against the result, copybook bytes -> entity -> database -> entity ->
 * bytes, PII at rest and in logs.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "logging.level.org.hibernate.SQL=DEBUG",
      "logging.level.org.hibernate.orm.jdbc.bind=TRACE"
    })
@Testcontainers
class DataModelIT {

  private static final Charset EBCDIC = Charset.forName("IBM037");
  private static final String SSN_LAST4 = "4821";
  private static final String TAX_ID = "987654321";
  private static final LocalDate DOB = LocalDate.of(1971, 3, 14);
  private static final int CREDIT_SCORE = 731;

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void piiKeys(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
    TestJwtIssuer.register(registry);
  }

  @Autowired Flyway flyway;
  @Autowired Environment environment;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;
  @Autowired TransactionTemplate tx;
  @Autowired PolicyNumberGenerator policyNumbers;
  @Autowired PolicyHolderRepository holders;
  @Autowired BlindIndexer blindIndexer;
  @Autowired UnderwritingDecisionRepository decisions;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    root().addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    root().detachAppender(logs);
  }

  private static Logger root() {
    return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  @Test
  void flywayAppliesAllMigrationsAndValidates() {
    assertThat(
            Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull))
        .extracting(Object::toString)
        .containsExactly("1", "2", "3", "4", "5", "6");
    flyway.validate();
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
  }

  @Test
  void db2KeysIndexesAndConstraintsArePorted() {
    List<String> constraints =
        jdbc.queryForList(
            "SELECT constraint_name FROM information_schema.table_constraints"
                + " WHERE table_schema = 'pas' AND constraint_type IN ('PRIMARY KEY','FOREIGN KEY')",
            String.class);
    assertThat(constraints)
        .contains(
            "pk_policies",
            "pk_coverages",
            "fk_cov_policy",
            "pk_premiums",
            "fk_prem_policy",
            "pk_endorsements",
            "fk_end_policy",
            "pk_uw_decisions",
            "fk_uwd_policy",
            "pk_policy_holders",
            "pk_terr_factors",
            "pk_class_factors");
    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'pas'", String.class);
    assertThat(indexes)
        .contains(
            "ix_pol_status",
            "ix_pol_holder",
            "ix_pol_agent",
            "ix_ph_name",
            "ix_ph_company",
            "ix_uwd_policy_date");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'pas'"
                    + " AND table_name = 'claims'",
                Integer.class))
        .isZero();
  }

  @Test
  void policySequenceMatchesDb2Definition() {
    Map<String, Object> seq =
        jdbc.queryForMap(
            "SELECT data_type::text AS data_type, start_value, increment_by, max_value, cycle,"
                + " cache_size FROM pg_sequences WHERE schemaname = 'pas'"
                + " AND sequencename = 'policy_seq'");
    assertThat(seq)
        .containsEntry("data_type", "integer")
        .containsEntry("start_value", 1_000_000L)
        .containsEntry("increment_by", 1L)
        .containsEntry("max_value", (long) Integer.MAX_VALUE)
        .containsEntry("cycle", false)
        .containsEntry("cache_size", 50L);
    long value = policyNumbers.nextSequenceValue();
    assertThat(value).isGreaterThanOrEqualTo(1_000_000L);
    assertThat(policyNumbers.nextPolicyNumber())
        .isEqualTo(PolicyNumbers.forNewBusiness(value + 1))
        .startsWith("POL")
        .hasSize(12);
  }

  @Test
  void parityFactorSetIsSeededAtOneAndFiledSetIsEmpty() {
    assertThat(
            jdbc.queryForObject(
                "SELECT rating_factor FROM pas.territory_factors WHERE factor_set = 'PARITY'"
                    + " AND territory_code = '*'",
                BigDecimal.class))
        .isEqualTo(new BigDecimal("1.0000"));
    assertThat(
            jdbc.queryForObject(
                "SELECT rating_factor FROM pas.class_factors WHERE factor_set = 'PARITY'"
                    + " AND class_code = '*'",
                BigDecimal.class))
        .isEqualTo(new BigDecimal("1.0000"));
    assertThat(
            jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM pas.territory_factors WHERE factor_set = 'FILED')"
                    + " + (SELECT count(*) FROM pas.class_factors WHERE factor_set = 'FILED')",
                Integer.class))
        .isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pas.class_factors VALUES ('PARITY', 'C0001', DATE '2024-01-01', 0)"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pas.territory_factors VALUES ('NOPE', 'TX0001', DATE '2024-01-01', 1)"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void foreignKeysToPoliciesAreEnforced() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pas.coverages (policy_number, sequence_num, coverage_type,"
                        + " effective_date, expiry_date) VALUES ('POLMISSING01', 1, 'PROP',"
                        + " DATE '2024-01-01', DATE '2025-01-01')"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** Quirk register Q-20: DB2 loses the second same-day decision; the target keeps both. */
  @Test
  void sameDayUnderwritingDecisionsAreAllKept() {
    String number = "POLQ20000001";
    LocalDate today = LocalDate.of(2025, 3, 1);
    tx.executeWithoutResult(
        s -> {
          entityManager.persist(CopybookMapping.toEntity(policy(number)));
          entityManager.persist(decision(number, today, "RF"));
          entityManager.persist(decision(number, today, "AP"));
        });
    List<UnderwritingDecisionEntity> kept =
        decisions.findByPolicyNumberAndDecisionDateOrderByDecisionIdAsc(number, today);
    assertThat(kept)
        .extracting(UnderwritingDecisionEntity::getDecisionCode)
        .containsExactly("RF", "AP");
    assertThat(kept.get(0).getDecisionId()).isLessThan(kept.get(1).getDecisionId());
  }

  private static UnderwritingDecisionEntity decision(String number, LocalDate date, String code) {
    UnderwritingDecisionEntity d = new UnderwritingDecisionEntity();
    d.setPolicyNumber(number);
    d.setDecisionDate(date);
    d.setDecisionCode(code);
    d.setRiskScore((short) 42);
    d.setCreatedTimestamp(LocalDateTime.of(2025, 3, 1, 9, 30));
    return d;
  }

  @Test
  void copybookRecordsRoundTripThroughTheDatabase() {
    String number = policyNumbers.nextPolicyNumber();
    byte[] policyBytes = policy(number).toBytes(EBCDIC);
    byte[] coverageBytes = coverage(number).toBytes(EBCDIC);
    byte[] premiumBytes = premium(number).toBytes(EBCDIC);
    byte[] customerBytes = customer("RT00000001").toBytes(EBCDIC);

    tx.executeWithoutResult(
        s -> {
          entityManager.persist(
              CopybookMapping.toEntity(PolicyRecord.fromBytes(policyBytes, EBCDIC)));
          entityManager.persist(
              CopybookMapping.toEntity(CoverageRecord.fromBytes(coverageBytes, EBCDIC)));
          entityManager.persist(
              CopybookMapping.toEntity(PremiumRecord.fromBytes(premiumBytes, EBCDIC)));
          entityManager.persist(
              CopybookMapping.toEntity(CustomerRecord.fromBytes(customerBytes, EBCDIC)));
        });

    tx.executeWithoutResult(
        s -> {
          PolicyEntity p = entityManager.find(PolicyEntity.class, number);
          assertThat(CopybookMapping.toRecord(p).toBytes(EBCDIC)).isEqualTo(policyBytes);
          CoverageEntity c =
              entityManager.find(CoverageEntity.class, new CoverageId(number, (short) 999));
          assertThat(CopybookMapping.toRecord(c).toBytes(EBCDIC)).isEqualTo(coverageBytes);
          PremiumEntity pr =
              entityManager.find(
                  PremiumEntity.class, new PremiumId(number, (short) 1, LocalDate.of(2024, 1, 1)));
          assertThat(pr.getBaseRate()).isEqualTo(new BigDecimal("-9999999.9999"));
          assertThat(CopybookMapping.toRecord(pr).toBytes(EBCDIC)).isEqualTo(premiumBytes);
          PolicyHolderEntity h = entityManager.find(PolicyHolderEntity.class, "RT00000001");
          assertThat(CopybookMapping.toRecord(h).toBytes(EBCDIC)).isEqualTo(customerBytes);
        });
  }

  @Test
  void piiIsEncryptedAtRestFindableByBlindIndexAndNeverLogged() {
    tx.executeWithoutResult(
        s -> holders.saveAndFlush(CopybookMapping.toEntity(customer("PII0000001"))));

    Map<String, Object> raw =
        jdbc.queryForMap(
            "SELECT date_of_birth_enc, ssn_last4_enc, ssn_last4_bidx, tax_id_enc, tax_id_bidx,"
                + " credit_score_enc FROM pas.policy_holders WHERE cust_id = 'PII0000001'");
    assertThat(raw.values()).allSatisfy(v -> assertThat(v).isNotNull());
    assertThat(String.join("|", raw.values().stream().map(Object::toString).toList()))
        .doesNotContain(SSN_LAST4, TAX_ID, DOB.toString());
    assertThat((String) raw.get("tax_id_bidx")).matches("[0-9a-f]{64}");

    List<PolicyHolderEntity> found = holders.findByTaxId(blindIndexer, "98-7654321");
    assertThat(found).extracting(PolicyHolderEntity::getCustId).containsExactly("PII0000001");
    PolicyHolderEntity holder = found.get(0);
    assertThat(holder.getSsnLast4()).isEqualTo(SSN_LAST4);
    assertThat(holder.getDateOfBirth()).isEqualTo(DOB);
    assertThat(holder.getCreditScore()).isEqualTo(CREDIT_SCORE);

    LoggerFactory.getLogger(DataModelIT.class).info("loaded holder {}", holder);
    LoggerFactory.getLogger(DataModelIT.class)
        .info("loaded record {}", CopybookMapping.toRecord(holder));

    assertThat(logs.list)
        .as("Hibernate bind-parameter logging was active, so the check is meaningful")
        .anyMatch(e -> e.getLoggerName().startsWith("org.hibernate.orm.jdbc.bind"));
    Pattern creditScore =
        Pattern.compile("(?<![A-Za-z0-9+/])" + CREDIT_SCORE + "(?![A-Za-z0-9+/=])");
    for (ILoggingEvent event : logs.list) {
      String text =
          event.getFormattedMessage()
              + (event.getThrowableProxy() == null
                  ? ""
                  : ThrowableProxyUtil.asString(event.getThrowableProxy()));
      assertThat(text)
          .as("log event from %s", event.getLoggerName())
          .doesNotContain(SSN_LAST4, TAX_ID, DOB.toString(), "19710314");
      assertThat(creditScore.matcher(text).find()).as(text).isFalse();
    }
  }

  private static PolicyRecord policy(String number) {
    return new PolicyRecord(
        number,
        "AUT",
        "AC",
        LocalDate.of(2024, 2, 29),
        LocalDate.of(2025, 2, 28),
        "CUST000001",
        "AG0001",
        "BR01",
        new BigDecimal("999999999.99"),
        new BigDecimal("-9999999.99"),
        new BigDecimal("99999999999.99"),
        null,
        999,
        "AP",
        0,
        "Y",
        "N",
        LocalDate.of(2025, 1, 1),
        "SYSTEM");
  }

  private static CoverageRecord coverage(String number) {
    return new CoverageRecord(
        number,
        999,
        "CYBR",
        "Cyber liability",
        new BigDecimal("-99999999999.99"),
        new BigDecimal("0.00"),
        new BigDecimal("0.01"),
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2025, 1, 1),
        "PN",
        100,
        "TX0001",
        "C1234");
  }

  private static PremiumRecord premium(String number) {
    return new PremiumRecord(
        number,
        1,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2025, 1, 1),
        new BigDecimal("-9999999.9999"),
        new BigDecimal("999.9999"),
        new BigDecimal("-999.9999"),
        new BigDecimal("0.0001"),
        new BigDecimal("1.0000"),
        new BigDecimal("-999.99"),
        new BigDecimal("-9999999.99"),
        new BigDecimal("0.00"),
        new BigDecimal("-999999999.99"),
        "MO",
        new BigDecimal("9999999.99"),
        LocalDate.of(2024, 1, 2),
        "PREMBAT");
  }

  private static CustomerRecord customer(String id) {
    return new CustomerRecord(
        id,
        "I",
        "Doe",
        "Jane",
        "Q",
        "",
        "1 Main St",
        "",
        "Austin",
        "TX",
        "73301",
        "USA",
        "5125550100",
        "jane@example.com",
        DOB,
        SSN_LAST4,
        TAX_ID,
        CREDIT_SCORE,
        "P",
        "Y",
        LocalDate.of(2020, 1, 2),
        LocalDate.of(2024, 6, 30));
  }
}
