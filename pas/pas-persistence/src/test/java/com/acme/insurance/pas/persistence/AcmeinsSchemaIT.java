package com.acme.insurance.pas.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.ClassFactor;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.Premium;
import com.acme.insurance.pas.domain.TerritoryFactor;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Applies the PostgreSQL migrations to an empty database and checks them against sql/ddl. */
@Testcontainers
class AcmeinsSchemaIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static MigrateResult migration;
  static JdbcClient jdbc;

  @BeforeAll
  static void migrate() {
    // One session, so POLICY_SEQ's CACHE 50 does not skip values between statements.
    SingleConnectionDataSource dataSource =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    migration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .load()
            .migrate();
    jdbc = JdbcClient.create(dataSource);
  }

  @Test
  void migrationsApplyCleanlyToAnEmptyDatabase() {
    assertThat(migration.success).isTrue();
    assertThat(migration.migrations)
        .extracting(m -> m.version)
        .contains("1", "10", "11", "12", "13");
    assertThat(tables("acmeins"))
        .containsExactlyInAnyOrder(
            "policies",
            "coverages",
            "premiums",
            "endorsements",
            "underwriting_decisions",
            "policy_holders",
            "territory_factors")
        .doesNotContain("claims");
    assertThat(tables("pasapp")).containsExactly("class_factors");
  }

  @Test
  void columnsMatchTheFrozenDb2Ddl() throws IOException {
    Map<String, Map<String, String>> expected = legacyColumns();
    assertThat(expected).hasSize(7);
    for (Map.Entry<String, Map<String, String>> table : expected.entrySet()) {
      Map<String, String> actual = new LinkedHashMap<>();
      jdbc.sql(
              """
              SELECT column_name, data_type, character_maximum_length, numeric_precision,
                     numeric_scale, is_nullable
              FROM information_schema.columns
              WHERE table_schema = 'acmeins' AND table_name = ? ORDER BY ordinal_position
              """)
          .param(table.getKey())
          .query(
              rs -> {
                actual.put(
                    rs.getString("column_name"),
                    pgType(
                            rs.getString("data_type"),
                            rs.getObject("character_maximum_length"),
                            rs.getObject("numeric_precision"),
                            rs.getObject("numeric_scale"))
                        + ("NO".equals(rs.getString("is_nullable")) ? " NOT NULL" : ""));
              });
      if (table.getKey().equals("underwriting_decisions")) {
        assertThat(actual.remove("decision_id")).isEqualTo("bigint NOT NULL");
      }
      assertThat(actual).as(table.getKey()).containsExactlyEntriesOf(table.getValue());
    }
  }

  @Test
  void keysAndForeignKeysMatchDb2WithoutAHolderForeignKey() {
    List<String> foreignKeys =
        jdbc.sql(
                """
                SELECT conname FROM pg_constraint
                WHERE contype = 'f' AND connamespace = 'acmeins'::regnamespace ORDER BY conname
                """)
            .query(String.class)
            .list();
    List<String> primaryKeys =
        jdbc.sql(
                """
                SELECT conrelid::regclass || ':' || pg_get_constraintdef(oid) FROM pg_constraint
                WHERE contype = 'p' AND connamespace = 'acmeins'::regnamespace ORDER BY 1
                """)
            .query(String.class)
            .list();

    assertThat(foreignKeys)
        .containsExactly("fk_cov_policy", "fk_end_policy", "fk_prem_policy", "fk_uwd_policy");
    assertThat(primaryKeys)
        .containsExactlyInAnyOrder(
            "acmeins.policies:PRIMARY KEY (policy_number)",
            "acmeins.coverages:PRIMARY KEY (policy_number, sequence_num)",
            "acmeins.premiums:PRIMARY KEY (policy_number, coverage_seq, term_effective_date)",
            "acmeins.endorsements:PRIMARY KEY (policy_number, endorsement_seq)",
            "acmeins.underwriting_decisions:PRIMARY KEY (decision_id)",
            "acmeins.policy_holders:PRIMARY KEY (cust_id)",
            "acmeins.territory_factors:PRIMARY KEY (territory_code, effective_date)");
  }

  @Test
  void policySequenceBehavesLikeDb2() {
    Map<String, Object> seq =
        jdbc.sql(
                """
                SELECT data_type::text AS type, start_value, increment_by, max_value, cycle,
                       cache_size
                FROM pg_sequences WHERE schemaname = 'acmeins' AND sequencename = 'policy_seq'
                """)
            .query()
            .singleRow();

    assertThat(seq)
        .containsEntry("type", "integer")
        .containsEntry("start_value", 1000000L)
        .containsEntry("increment_by", 1L)
        .containsEntry("max_value", 2147483647L)
        .containsEntry("cycle", false)
        .containsEntry("cache_size", 50L);
    PolicyJdbcRepository repository = new PolicyJdbcRepository(jdbc);
    long first = repository.nextPolicySequence();
    assertThat(first).isGreaterThanOrEqualTo(1000000L);
    assertThat(repository.nextPolicySequence()).isEqualTo(first + 1);
  }

  @Test
  void defaultsMatchDb2() {
    jdbc.sql(
            """
            INSERT INTO acmeins.policies (policy_number, policy_type, effective_date, expiry_date,
              policyholder_id) VALUES ('POL-DEFAULTS', 'AUT', DATE '2024-01-01',
              DATE '2025-01-01', 'CUST000009')
            """)
        .update();

    Policy policy = new PolicyJdbcRepository(jdbc).findPolicy("POL-DEFAULTS").orElseThrow();

    assertThat(policy.policyStatusCode()).isEqualTo("PN");
    assertThat(policy.uwStatusCode()).isEqualTo("PN");
    assertThat(policy.updatedBy()).isEqualTo("SYSTEM");
    assertThat(policy.totalPremium()).isEqualTo(new BigDecimal("0.00"));
    assertThat(policy.webIndicator()).isEqualTo("N");
    assertThat(policy.renewalCount()).isZero();
    assertThat(policy.lastUpdated()).isNotNull();
  }

  @Test
  void repositoryInsertsApplyNotNullDefaultsAndKeepExplicitNulls() {
    PolicyJdbcRepository policies = new PolicyJdbcRepository(jdbc);
    PolicyHolderJdbcRepository holders = new PolicyHolderJdbcRepository(jdbc);

    policies.insert(
        new Policy(
            "POL-REPODFLT",
            "HOM",
            null,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2025, 1, 1),
            "CUST000088",
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
            null));
    holders.insert(
        new PolicyHolder(
            "CUST000088",
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
            null,
            null,
            null,
            null,
            null,
            null));

    Policy policy = policies.findPolicy("POL-REPODFLT").orElseThrow();
    assertThat(policy.policyStatusCode()).isEqualTo("PN");
    assertThat(policy.updatedBy()).isEqualTo("SYSTEM");
    assertThat(policy.totalPremium()).isNull();
    assertThat(policy.lastUpdated()).isNotNull();
    assertThat(policy.agentCode()).isNull();
    PolicyHolder holder = holders.find("CUST000088").orElseThrow();
    assertThat(holder.custTypeCode()).isEqualTo("I");
    assertThat(holder.countryCode()).isNull();
    assertThat(holder.riskTierCode()).isNull();
    assertThat(holder.createdDate()).isNotNull();
    assertThat(holder.lastUpdated()).isNotNull();
  }

  @Test
  void policyCoverageAndPremiumRoundTripAtColumnLimits() {
    PolicyJdbcRepository repository = new PolicyJdbcRepository(jdbc);
    Policy policy =
        new Policy(
            "POL-ROUNDTRP",
            "CGL",
            "ZZ",
            LocalDate.of(2024, 2, 29),
            LocalDate.of(2025, 2, 28),
            "NOHOLDER01",
            "AG0001",
            "NY01",
            new BigDecimal("999999999.99"),
            new BigDecimal("-9999999.99"),
            new BigDecimal("99999999999.99"),
            null,
            32767,
            "RM",
            -1,
            "Y",
            "N",
            LocalDateTime.of(2024, 3, 1, 14, 30, 5, 123456000),
            "POLNEW");
    Coverage coverage =
        new Coverage(
            "POL-ROUNDTRP",
            1,
            "DWEL",
            "Seed-only coverage type (Q-35)",
            new BigDecimal("-99999999999.99"),
            new BigDecimal("0.01"),
            new BigDecimal("0.00"),
            LocalDate.of(2024, 1, 1),
            LocalDate.of(9999, 12, 31),
            "AC",
            100,
            "T00001",
            "C1234");
    Premium premium =
        new Premium(
            "POL-ROUNDTRP",
            1,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 12, 31),
            new BigDecimal("9999999.9999"),
            new BigDecimal("999.9999"),
            new BigDecimal("0.0001"),
            new BigDecimal("-999.9999"),
            new BigDecimal("1.0000"),
            new BigDecimal("-999.99"),
            new BigDecimal("9999999.99"),
            new BigDecimal("0.00"),
            new BigDecimal("999999999.99"),
            "MO",
            new BigDecimal("-0.01"),
            LocalDate.of(2024, 1, 2),
            "PREMBAT");

    repository.insert(policy);
    repository.insert(coverage);
    repository.insert(premium);

    assertThat(repository.findPolicy("POL-ROUNDTRP")).contains(policy);
    assertThat(repository.findCoverages("POL-ROUNDTRP")).containsExactly(coverage);
    assertThat(repository.findPremiums("POL-ROUNDTRP")).containsExactly(premium);
  }

  @Test
  void policyHolderRoundTripsAndTheMaskedViewHidesRestrictedValues() {
    PolicyHolder holder =
        new PolicyHolder(
            "CUST000077",
            "I",
            "Zzsynthetic",
            "Zztest",
            "Q",
            "Zz Holdings",
            "1 Example St",
            null,
            "Springfield",
            "IL",
            "62701",
            "USA",
            "555-0100",
            "zz@example.invalid",
            LocalDate.of(1970, 1, 1),
            "0000",
            "0000000000",
            700,
            "S",
            "Y",
            LocalDate.of(2020, 1, 1),
            LocalDateTime.of(2024, 1, 1, 0, 0));
    PolicyHolderJdbcRepository repository = new PolicyHolderJdbcRepository(jdbc);

    repository.insert(holder);

    assertThat(repository.find("CUST000077")).contains(holder);
    Map<String, Object> masked =
        jdbc.sql("SELECT * FROM acmeins.policy_holders_masked WHERE cust_id = 'CUST000077'")
            .query()
            .singleRow();
    assertThat(masked)
        .containsEntry("last_name", "Z***")
        .containsEntry("company_name", "Z***")
        .containsEntry("email", "***@example.invalid")
        .containsEntry("ssn_last4", "****")
        .containsEntry("tax_id", "**********")
        .containsEntry("birth_year", 1970)
        .doesNotContainKeys("date_of_birth", "credit_score", "phone", "addr_line1", "zip_code");
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'acmeins' AND table_name = 'policy_holders'
                      AND col_description('acmeins.policy_holders'::regclass, ordinal_position)
                          LIKE 'PII: RESTRICTED%'
                    """)
                .query(Integer.class)
                .single())
        .isEqualTo(4);
  }

  @Test
  void sameDayUnderwritingDecisionsAreAllKept() {
    jdbc.sql(
            """
            INSERT INTO acmeins.policies (policy_number, policy_type, effective_date, expiry_date,
              policyholder_id) VALUES ('POL-UWTWICE', 'AUT', DATE '2024-01-01', DATE '2025-01-01',
              'CUST000010')
            """)
        .update();
    for (String code : List.of("RS", "AP")) {
      jdbc.sql(
              """
              INSERT INTO acmeins.underwriting_decisions (policy_number, decision_date,
                decision_code, underwriter_id) VALUES ('POL-UWTWICE', DATE '2024-01-02', ?, 'SYSTEM')
              """)
          .param(code)
          .update();
    }

    assertThat(
            jdbc.sql(
                    "SELECT decision_code FROM acmeins.underwriting_decisions"
                        + " WHERE policy_number = 'POL-UWTWICE' ORDER BY decision_id")
                .query(String.class)
                .list())
        .containsExactly("RS", "AP");
  }

  @Test
  void ratingFactorsAreEffectiveDatedAndPositive() {
    RatingFactorJdbcRepository repository = new RatingFactorJdbcRepository(jdbc);
    repository.insert(
        new TerritoryFactor("T00042", LocalDate.of(2023, 1, 1), new BigDecimal("1.1000")));
    repository.insert(
        new TerritoryFactor("T00042", LocalDate.of(2024, 7, 1), new BigDecimal("1.2500")));
    repository.insert(new ClassFactor("C0042", LocalDate.of(2024, 1, 1), new BigDecimal("0.8750")));

    assertThat(repository.territoryFactor("T00042", LocalDate.of(2022, 12, 31))).isEmpty();
    assertThat(repository.territoryFactor("T00042", LocalDate.of(2024, 6, 30)))
        .contains(new BigDecimal("1.1000"));
    assertThat(repository.territoryFactor("T00042", LocalDate.of(2024, 7, 1)))
        .contains(new BigDecimal("1.2500"));
    assertThat(repository.classFactor("C0042", LocalDate.of(2024, 1, 1)))
        .contains(new BigDecimal("0.8750"));
    assertThat(repository.classFactor("NONE", LocalDate.of(2024, 1, 1))).isEmpty();
    assertThatThrownBy(
            () ->
                jdbc.sql("INSERT INTO pasapp.class_factors VALUES ('C0000', DATE '2024-01-01', 0)")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_class_factor_positive");
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO acmeins.territory_factors VALUES ('T0', DATE '2024-01-01', -1)")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_terr_factor_positive");
  }

  private static List<String> tables(String schema) {
    return jdbc.sql(
            "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = ? AND table_type = 'BASE TABLE'")
        .param(schema)
        .query(String.class)
        .list();
  }

  private static String pgType(String dataType, Object length, Object precision, Object scale) {
    return switch (dataType) {
      case "character" -> "char(" + length + ")";
      case "character varying" -> "varchar(" + length + ")";
      case "numeric" -> "decimal(" + precision + "," + scale + ")";
      case "timestamp without time zone" -> "timestamp";
      default -> dataType;
    };
  }

  /** Parses the CREATE TABLE statements of the frozen DB2 DDL into column -> normalised type. */
  private static Map<String, Map<String, String>> legacyColumns() throws IOException {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isRegularFile(dir.resolve("sql/ddl/create-tables.sql"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repository root containing sql/ddl").isNotNull();
    String ddl = Files.readString(dir.resolve("sql/ddl/create-tables.sql"));
    Pattern table =
        Pattern.compile("CREATE TABLE ACMEINS\\.(\\w+)\\s*\\((.*?)\\)\\s*IN ", Pattern.DOTALL);
    Pattern column = Pattern.compile("^\\s*(\\w+)\\s+(\\w+(?:\\(\\d+(?:,\\d+)?\\))?)(.*)$");
    Map<String, Map<String, String>> result = new LinkedHashMap<>();
    Matcher t = table.matcher(ddl);
    while (t.find()) {
      Map<String, String> columns = new LinkedHashMap<>();
      for (String line : t.group(2).split("\n")) {
        String code = line.replaceAll("--.*", "");
        Matcher c = column.matcher(code);
        if (!c.matches() || c.group(1).equals("CONSTRAINT") || c.group(1).equals("REFERENCES")) {
          continue;
        }
        String type = c.group(2).toLowerCase(Locale.ROOT).replace("integer", "integer");
        columns.put(
            c.group(1).toLowerCase(Locale.ROOT),
            type + (c.group(3).contains("NOT NULL") ? " NOT NULL" : ""));
      }
      result.put(t.group(1).toLowerCase(Locale.ROOT), columns);
    }
    return result;
  }
}
