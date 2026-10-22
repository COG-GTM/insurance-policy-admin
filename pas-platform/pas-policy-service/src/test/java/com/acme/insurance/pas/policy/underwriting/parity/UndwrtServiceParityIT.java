package com.acme.insurance.pas.policy.underwriting.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.CustomerType;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.RiskTier;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.parity.ParityHarness;
import com.acme.insurance.pas.policy.claims.ClaimsFixtureProperties;
import com.acme.insurance.pas.policy.claims.FixtureClaimsHistoryAdapter;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.repository.PolicyHolderRepository;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import com.acme.insurance.pas.policy.underwriting.UnderwritingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("it")
@Import(UndwrtServiceParityIT.SecurityTestConfiguration.class)
class UndwrtServiceParityIT {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .setNodeFactory(
              com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
  private static final BigDecimal MAX_POLICY_LIMIT = new BigDecimal("99999999999.99");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PolicyHolderRepository holders;
  @Autowired private PolicyRepository policies;
  @Autowired private UnderwritingService underwriting;
  @Autowired private ClaimsHistoryPort claims;
  @Autowired private MutableClock clock;

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityTestConfiguration {
    @Bean
    JwtDecoder jwtDecoder() {
      return token -> null;
    }

    @Bean
    @Primary
    MutableClock parityClock() {
      return new MutableClock(Instant.parse("2024-10-15T00:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Test
  void matchesTheRealServiceToEveryUndwrtFixture() throws Exception {
    Path root = ParityHarness.locateRoot();
    JsonNode defaults =
        MAPPER.readTree(root.resolve("parity/programs/UNDWRT/spec.json").toFile()).path("defaults");
    List<Path> fixtures;
    try (Stream<Path> stream = Files.list(root.resolve("parity/fixtures/UNDWRT"))) {
      fixtures =
          stream
              .filter(path -> path.getFileName().toString().endsWith(".json"))
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .toList();
    }
    assertThat(fixtures).isNotEmpty();
    UndwrtParityAdapter adapter = new UndwrtParityAdapter();

    for (Path fixturePath : fixtures) {
      clearData();
      JsonNode fixture = MAPPER.readTree(fixturePath.toFile());
      UndwrtParityAdapter.Input input =
          MAPPER.treeToValue(
              merge(defaults, fixture.path("input")), UndwrtParityAdapter.Input.class);
      LocalDate today = LocalDate.parse(input.currentDate(), DateTimeFormatter.BASIC_ISO_DATE);
      clock.set(today);
      UndwrtParityAdapter.Output expected = adapter.run(input);

      if (input.policySqlcode() != 0 || input.receiveResp() != 0) {
        assertNoPolicyWrites();
        assertThat(expected.error()).isNotEmpty();
        continue;
      }

      String custId = "C000000001";
      seedHolder(input, custId, today);
      seedPolicies(input, custId, today);
      FixtureClaimsHistoryAdapter fixtureClaims = (FixtureClaimsHistoryAdapter) claims;
      fixtureClaims.put(
          input.policy().policyNumber(),
          new ClaimsFixtureProperties.Fixture(
              input.claims().count(), input.claims().totalIncurred(), 0));

      underwriting.decide(
          input.policy().policyNumber(),
          new CallerEntitlements(true, null, List.of()),
          "parity-test",
          "parity-test",
          false);

      assertThat(actualOutput(input.policy().policyNumber()))
          .as(fixturePath.getFileName().toString())
          .isEqualTo(expected);
    }
  }

  private void seedHolder(UndwrtParityAdapter.Input input, String custId, LocalDate today) {
    var customer = input.customer();
    holders.insert(
        new PolicyHolder(
            custId,
            Coded.of(CustomerType.class, customer.custType()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            customer.creditScore(),
            customer.riskTier() == null ? null : Coded.of(RiskTier.class, customer.riskTier()),
            null,
            today,
            OffsetDateTime.now(clock)));
  }

  private void seedPolicies(UndwrtParityAdapter.Input input, String custId, LocalDate today) {
    var source = input.policy();
    seedPolicy(source.policyNumber(), source, custId, today, false, source.coverageLimit());
    BigDecimal remaining = input.branchAccumulatedLimit();
    int sequence = 2;
    while (remaining != null && remaining.signum() > 0) {
      BigDecimal amount = remaining.min(MAX_POLICY_LIMIT);
      seedPolicy(policyNumber(sequence++), source, custId, today, true, amount);
      remaining = remaining.subtract(amount);
    }
  }

  private void seedPolicy(
      String policyNumber,
      UndwrtParityAdapter.Policy source,
      String custId,
      LocalDate today,
      boolean active,
      BigDecimal coverageLimit) {
    policies.insert(
        new Policy(
            policyNumber,
            Coded.of(PolicyType.class, source.policyType()),
            Coded.of(PolicyStatus.class, active ? "AC" : "PN"),
            today,
            today.plusYears(1),
            custId,
            "AGT001",
            source.branchCode(),
            source.totalPremium(),
            BigDecimal.ZERO.setScale(2),
            coverageLimit,
            today,
            0,
            Coded.of(UnderwritingStatus.class, "PN"),
            0,
            Coded.of(YesNo.class, "N"),
            Coded.of(YesNo.class, "N"),
            OffsetDateTime.now(clock),
            "parity"));
  }

  private UndwrtParityAdapter.Output actualOutput(String policyNumber) {
    UndwrtParityAdapter.DecisionRow decision =
        jdbc.queryForObject(
            """
            SELECT policy_number, decision_date, decision_code, risk_score, decision_reason
            FROM pas.underwriting_decisions WHERE policy_number = ?
            """,
            (row, rowNumber) ->
                new UndwrtParityAdapter.DecisionRow(
                    row.getString("policy_number").trim(),
                    row.getDate("decision_date")
                        .toLocalDate()
                        .format(DateTimeFormatter.BASIC_ISO_DATE),
                    row.getString("decision_code").trim(),
                    row.getInt("risk_score"),
                    row.getString("decision_reason").trim()),
            policyNumber);
    UndwrtParityAdapter.PolicyUpdateRow policyUpdate =
        jdbc.queryForObject(
            "SELECT uw_status, risk_score FROM pas.policies WHERE policy_number = ?",
            (row, rowNumber) ->
                new UndwrtParityAdapter.PolicyUpdateRow(
                    row.getString("uw_status").trim(), row.getInt("risk_score")),
            policyNumber);
    UndwrtParityAdapter.NotificationRow notification =
        jdbc.queryForObject(
            """
            SELECT payload -> 'data' ->> 'decisionCode' AS decision_code,
                   payload -> 'data' ->> 'riskScore' AS risk_score
            FROM pas.outbox
            WHERE aggregate_id = ? AND event_type = 'UnderwritingDecisionRecorded'
            """,
            (row, rowNumber) ->
                new UndwrtParityAdapter.NotificationRow(
                    policyNumber,
                    row.getString("decision_code"),
                    Integer.parseInt(row.getString("risk_score"))),
            policyNumber);
    return new UndwrtParityAdapter.Output(
        List.of(decision), List.of(policyUpdate), List.of(notification), List.of());
  }

  private void assertNoPolicyWrites() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.policies", Integer.class)).isZero();
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM pas.underwriting_decisions", Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.outbox", Integer.class)).isZero();
  }

  private void clearData() {
    jdbc.update("DELETE FROM pas.outbox");
    jdbc.update("DELETE FROM pas.underwriting_decisions");
    jdbc.update("DELETE FROM pas.coverages");
    jdbc.update("DELETE FROM pas.policies");
    jdbc.update("DELETE FROM pas.policy_holders");
  }

  private static String policyNumber(int sequence) {
    String digits = Integer.toString(sequence);
    return "POL" + "000000000".substring(digits.length()) + digits;
  }

  private static JsonNode merge(JsonNode base, JsonNode override) {
    if (!base.isObject() || !override.isObject()) {
      return override.deepCopy();
    }
    ObjectNode merged = base.deepCopy();
    override
        .fields()
        .forEachRemaining(
            entry ->
                merged.set(
                    entry.getKey(),
                    merge(
                        base.has(entry.getKey()) ? base.get(entry.getKey()) : entry.getValue(),
                        entry.getValue())));
    return merged;
  }

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    MutableClock(Instant initial, ZoneId zone) {
      this(new AtomicReference<>(initial), zone);
    }

    private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    void set(LocalDate date) {
      instant.set(date.atStartOfDay(zone).toInstant());
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
      return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
      return instant.get();
    }
  }
}
