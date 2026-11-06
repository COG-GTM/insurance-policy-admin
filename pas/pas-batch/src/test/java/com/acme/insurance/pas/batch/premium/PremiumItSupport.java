package com.acme.insurance.pas.batch.premium;

import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Test data for the premium job integration tests. */
final class PremiumItSupport {

  @TestConfiguration(proxyBeanMethods = false)
  static class Postgres {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
      return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
  }

  private static final List<String> TYPES = List.of("AUT", "HOM", "COM", "LIF", "HLT");

  private final JdbcTemplate jdbc;

  PremiumItSupport(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void reset() {
    acceptAllPremiums();
    jdbc.update("DELETE FROM pasapp.premium_run_item");
    jdbc.update("DELETE FROM acmeins.premiums");
    jdbc.update("DELETE FROM acmeins.policies");
  }

  void policy(String number, String type, String effective, String expiry, String status) {
    jdbc.update(
        """
        INSERT INTO acmeins.policies (policy_number, policy_type, policy_status, effective_date,
          expiry_date, policyholder_id, total_premium, deductible, coverage_limit)
        VALUES (?, ?, ?, CAST(? AS date), CAST(? AS date), 'H000000001', 1000.00, 500.00, 250000.00)
        """,
        number,
        type,
        status,
        effective,
        expiry);
  }

  /** {@code count} active policies POL-00000100.. cycling through the five policy types. */
  void book(int count) {
    for (int i = 0; i < count; i++) {
      policy(
          String.format("POL-%08d", 100 + i),
          TYPES.get(i % TYPES.size()),
          "2025-01-01",
          "2026-01-01",
          "AC");
    }
  }

  /** Makes every PREMIUMS write of one policy fail with the given SQLSTATE. */
  void rejectPremiumsOf(String policyNumber, String sqlState) {
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION acmeins.reject_premium() RETURNS trigger AS $$ BEGIN"
            + " IF NEW.policy_number = '"
            + policyNumber
            + "' THEN RAISE EXCEPTION 'rejected by test' USING ERRCODE = '"
            + sqlState
            + "'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER reject_premium BEFORE INSERT OR UPDATE ON acmeins.premiums"
            + " FOR EACH ROW EXECUTE FUNCTION acmeins.reject_premium()");
  }

  void acceptAllPremiums() {
    jdbc.execute("DROP TRIGGER IF EXISTS reject_premium ON acmeins.premiums");
  }

  List<Map<String, Object>> premiums() {
    return jdbc
        .queryForList(
            "SELECT * FROM acmeins.premiums ORDER BY policy_number, coverage_seq, term_effective_date")
        .stream()
        .peek(r -> r.computeIfPresent("policy_number", (k, v) -> v.toString().stripTrailing()))
        .peek(r -> r.computeIfPresent("calc_by", (k, v) -> v.toString().stripTrailing()))
        .toList();
  }
}
