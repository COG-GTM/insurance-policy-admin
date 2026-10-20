package com.acme.insurance.pas.policy.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.security.PasScopes;
import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AB-528 acceptance "the Broker Portal works with no connection to DB2": every portal query in
 * {@code docs/modernization/broker-portal-migration.md} is served by pas-policy-service from
 * PostgreSQL, with no DB2 driver on the classpath and no DB2 datasource.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BrokerPortalApiIT {

  static final Path MIGRATION_DOC = Path.of("../../docs/modernization/broker-portal-migration.md");
  private static final Pattern ROW =
      Pattern.compile("^\\| (PQ-\\d+) \\|.*\\| `GET ([^`]+)` \\|\\s*$", Pattern.MULTILINE);

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
    TestJwtIssuer.register(registry);
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PolicyHolderRepository holders;
  @Autowired private ObjectMapper mapper;

  @Value("${spring.datasource.url}")
  private String datasourceUrl;

  private static boolean seeded;

  @BeforeEach
  void seedOnce() {
    if (!seeded) {
      FacadeSeed.load(jdbc, holders);
      jdbc.update(
          "INSERT INTO pas.premiums (policy_number, coverage_seq, term_effective_date,"
              + " term_expiry_date, total_premium, installment_code, calc_date, calc_by)"
              + " VALUES ('POL-00000001', 1, ?, ?, 1250.00, 'MO', ?, 'PREMBAT')",
          LocalDate.of(2024, 1, 1),
          LocalDate.of(2025, 1, 1),
          LocalDate.of(2024, 1, 2));
      seeded = true;
    }
  }

  @Test
  void everyPortalQueryIsServedWithoutDb2() throws Exception {
    assertThatThrownBy(() -> Class.forName("com.ibm.db2.jcc.DB2Driver"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThat(datasourceUrl).startsWith("jdbc:postgresql:").doesNotContainIgnoringCase("db2");

    List<String[]> queries = portalQueries();
    assertThat(queries).extracting(q -> q[0]).contains("PQ-01", "PQ-04", "PQ-06");
    String bearer = TestJwtIssuer.bearer().scopes(PasScopes.POLICY_READ).book("*").sign();
    for (String[] q : queries) {
      ResponseEntity<String> res = get(q[1], bearer);
      assertThat(res.getStatusCode().value()).as(q[0] + " " + q[1]).isEqualTo(200);
      assertThat(mapper.readTree(res.getBody())).as(q[0] + " body").isNotNull();
    }
    JsonNode book = mapper.readTree(get("/api/v1/policies?agentCode=AG1001", bearer).getBody());
    assertThat(book).isNotEmpty();
    assertThat(book).allSatisfy(p -> assertThat(p.get("agentCode").asText()).isEqualTo("AG1001"));
    JsonNode premiums =
        mapper.readTree(get("/api/v1/policies/POL-00000001/premiums", bearer).getBody());
    assertThat(premiums).hasSize(1);
    assertThat(premiums.get(0).get("termEffectiveDate").asText()).isEqualTo("2024-01-01");
    assertThat(premiums.get(0).get("totalPremium").decimalValue()).isEqualByComparingTo("1250.00");
  }

  @Test
  void bookListingAndPremiumsFollowObjectAuthorization() throws Exception {
    String otherAgent =
        TestJwtIssuer.bearer().scopes(PasScopes.POLICY_READ).agent("AG9999").branch("NYC9").sign();
    assertThat(mapper.readTree(get("/api/v1/policies?agentCode=AG1001", otherAgent).getBody()))
        .isEmpty();
    assertThat(get("/api/v1/policies/POL-00000001/premiums", otherAgent).getStatusCode().value())
        .isEqualTo(404);

    String reader = TestJwtIssuer.bearer().scopes(PasScopes.POLICY_READ).book("*").sign();
    assertThat(get("/api/v1/policies", reader).getStatusCode().value()).isEqualTo(400);
    assertThat(get("/api/v1/policies?agentCode=AG1001&size=0", reader).getStatusCode().value())
        .isEqualTo(400);
    String noScope = TestJwtIssuer.bearer().scopes("openid").book("*").sign();
    assertThat(get("/api/v1/policies?agentCode=AG1001", noScope).getStatusCode().value())
        .isEqualTo(403);
    assertThat(
            rest.getForEntity("/api/v1/policies?agentCode=AG1001", String.class)
                .getStatusCode()
                .value())
        .isEqualTo(401);
  }

  private static List<String[]> portalQueries() throws Exception {
    Matcher m = ROW.matcher(Files.readString(MIGRATION_DOC));
    List<String[]> out = new ArrayList<>();
    while (m.find()) {
      out.add(new String[] {m.group(1), m.group(2).strip()});
    }
    return out;
  }

  private ResponseEntity<String> get(String path, String bearer) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(bearer);
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
