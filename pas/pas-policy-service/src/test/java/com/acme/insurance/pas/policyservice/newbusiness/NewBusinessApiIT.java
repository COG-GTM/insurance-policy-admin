package com.acme.insurance.pas.policyservice.newbusiness;

import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_WRITE;
import static com.acme.insurance.pas.policyservice.security.PasScopes.UNDERWRITING_DECIDE;
import static com.acme.insurance.pas.policyservice.security.PasScopes.UNDERWRITING_OVERRIDE;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policyservice.TestJwt;
import com.acme.insurance.pas.policyservice.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * AB-525 acceptance, end to end: create-policy and underwriting through the HTTP API with real
 * token validation, PostgreSQL, the outbox relay and Kafka. The events are read back by a plain
 * Kafka subscriber; claims history comes from a fake Claims endpoint over HTTP.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "pas.events.relay-enabled=true",
      "pas.events.create-topics=true",
      "pas.events.relay-interval=PT0.2S",
      "pas.business-zone=UTC"
    })
@Import(TestcontainersConfiguration.class)
@Testcontainers
class NewBusinessApiIT {

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  static HttpServer claims;
  static final Map<String, String> CLAIMS = new HashMap<>();

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

  @BeforeAll
  static void claimsEndpoint() throws Exception {
    claims = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    claims.createContext(
        "/v1/policies/",
        exchange -> {
          String pn = exchange.getRequestURI().getPath().split("/")[3];
          String body = CLAIMS.get(pn);
          int status = body == null ? 503 : 200;
          byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    claims.start();
  }

  @AfterAll
  static void stop() {
    claims.stop(0);
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
    registry.add("pas.claims.base-url", () -> "http://127.0.0.1:" + claims.getAddress().getPort());
  }

  void holder(String id, int credit, String tier) {
    jdbc.sql("DELETE FROM acmeins.policy_holders WHERE cust_id = ?").param(id).update();
    jdbc.sql(
            "INSERT INTO acmeins.policy_holders (cust_id, cust_type, last_name, credit_score,"
                + " risk_tier) VALUES (?, 'I', 'Testholder', ?, ?)")
        .params(id, credit, tier)
        .update();
  }

  String body(
      String type, String holder, String agent, String branch, LocalDate eff, String limit) {
    return "{\"policyType\":\"%s\",\"policyholderId\":\"%s\",\"agentCode\":\"%s\",\"branchCode\":\"%s\",\"effectiveDate\":\"%s\",\"totalPremium\":1000.00,\"deductible\":250.00,\"coverageLimit\":%s}"
        .formatted(type, holder, agent, branch, eff, limit);
  }

  HttpResponse<String> post(String path, String jwt, String body) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (jwt != null) {
      b.header("Authorization", "Bearer " + jwt);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path, String jwt) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + jwt)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static LocalDate today() {
    return LocalDate.now(ZoneId.of("UTC"));
  }

  List<ConsumerRecord<String, String>> consume(String policyNumber, int expected) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + System.nanoTime());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(
          List.of("pas.policy.created", "pas.underwriting.requested", "pas.underwriting.decided"));
      long deadline = System.currentTimeMillis() + 30_000;
      while (found.size() < expected && System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          if (policyNumber.equals(r.key())) {
            found.add(r);
          }
        }
      }
    }
    return found;
  }

  @Test
  void createPolicyUnderwriteAndOverrideEndToEnd() throws Exception {
    holder("C525000001", 101, "U");
    String writer =
        TestJwt.sign(
            TestJwt.claims(POLICY_WRITE + " " + POLICY_READ + " " + UNDERWRITING_DECIDE)
                .claim("pas_branches", List.of("B525"))
                .build());
    HttpResponse<String> created =
        post(
            "/api/v1/policies",
            writer,
            body("HOM", "C525000001", "AG0525", "B525", today(), "250000.00"));
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    JsonNode policy = json.readTree(created.body());
    String pn = policy.get("policyNumber").asText();
    assertThat(pn).matches("POL-\\d{8}");
    assertThat(created.headers().firstValue("Location")).contains("/api/v1/policies/" + pn);
    assertThat(policy.get("policyStatus").asText()).isEqualTo("PN");
    assertThat(policy.get("uwStatus").asText()).isEqualTo("PN");
    assertThat(policy.get("expiryDate").asText()).isEqualTo(today().plusMonths(12).toString());
    assertThat(policy.get("totalPremium").decimalValue()).isEqualByComparingTo("1000.00");
    assertThat(policy.get("coverages").findValuesAsText("coverageType"))
        .containsExactly("PROP", "LIAB");

    // Readable through the AB-524 inquiry API.
    HttpResponse<String> inquiry = get("/api/v1/policies/" + pn + "/coverages", writer);
    assertThat(inquiry.statusCode()).isEqualTo(200);
    assertThat(json.readTree(inquiry.body())).hasSize(2);
    assertThat(
            jdbc.sql("SELECT updated_by FROM acmeins.policies WHERE policy_number = ?")
                .param(pn)
                .query(String.class)
                .single()
                .trim())
        .isEqualTo("POLNEW");

    // Events visible to a subscriber.
    List<ConsumerRecord<String, String>> events = consume(pn, 2);
    assertThat(events)
        .extracting(ConsumerRecord::topic)
        .containsExactlyInAnyOrder("pas.policy.created", "pas.underwriting.requested");
    JsonNode createdEvent =
        json.readTree(
            events.stream()
                .filter(r -> r.topic().equals("pas.policy.created"))
                .findFirst()
                .orElseThrow()
                .value());
    assertThat(createdEvent.get("data").get("policyholderId").asText()).isEqualTo("C525000001");
    assertThat(createdEvent.toString()).doesNotContain("Testholder");
    assertThat(events.get(0).headers().lastHeader("eventId")).isNotNull();

    // Audit row with the full principal.
    assertThat(
            jdbc.sql(
                    "SELECT principal FROM pasapp.policy_audit WHERE policy_number = ? AND action = 'POLICY_CREATED'")
                .param(pn)
                .query(String.class)
                .list())
        .containsExactly("test-user");

    // Underwriting: credit 101 substandard = 599, + loss ratio 90% = 799 -> RM.
    CLAIMS.put(pn, "{\"policyNumber\":\"" + pn + "\",\"claimCount\":1,\"totalIncurred\":900.00}");
    HttpResponse<String> decided =
        post("/api/v1/policies/" + pn + "/underwriting-decisions", writer, "");
    assertThat(decided.statusCode()).as(decided.body()).isEqualTo(201);
    JsonNode d = json.readTree(decided.body());
    assertThat(d.get("decisionCode").asText()).isEqualTo("RM");
    assertThat(d.get("riskScore").asInt()).isEqualTo(799);
    assertThat(d.get("underwriterId").asText()).isEqualTo("SYSTEM");
    assertThat(
            jdbc.sql("SELECT uw_status FROM acmeins.policies WHERE policy_number = ?")
                .param(pn)
                .query(String.class)
                .single())
        .isEqualTo("RM");
    assertThat(consume(pn, 3))
        .extracting(ConsumerRecord::topic)
        .contains("pas.underwriting.decided");

    // Override needs its own scope.
    String override = "{\"decisionCode\":\"AP\",\"reason\":\"Inspection passed\"}";
    assertThat(
            post("/api/v1/policies/" + pn + "/underwriting-decisions/override", writer, override)
                .statusCode())
        .isEqualTo(403);
    String manager =
        TestJwt.sign(
            TestJwt.claims(UNDERWRITING_OVERRIDE + " " + POLICY_READ)
                .claim("pas_book", "*")
                .claim("pas_legacy_user", "UW00042")
                .build());
    HttpResponse<String> overridden =
        post("/api/v1/policies/" + pn + "/underwriting-decisions/override", manager, override);
    assertThat(overridden.statusCode()).as(overridden.body()).isEqualTo(200);
    assertThat(json.readTree(overridden.body()).get("overrideBy").asText()).isEqualTo("UW00042");
    JsonNode history =
        json.readTree(get("/api/v1/policies/" + pn + "/underwriting-decisions", manager).body());
    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("decisionCode").asText()).isEqualTo("AP");
    assertThat(
            jdbc.sql(
                    "SELECT action FROM pasapp.policy_audit WHERE policy_number = ? ORDER BY audit_id")
                .param(pn)
                .query(String.class)
                .list())
        .containsExactly("POLICY_CREATED", "UNDERWRITING_DECIDED", "UNDERWRITING_OVERRIDDEN");
  }

  @Test
  void onlyAuthorisedAndEntitledCallersMayWrite() throws Exception {
    holder("C525000002", 700, "S");
    String body = body("AUT", "C525000002", "AG0001", "B001", today(), "100000.00");
    assertThat(post("/api/v1/policies", null, body).statusCode()).isEqualTo(401);
    assertThat(post("/api/v1/policies", "garbage", body).statusCode()).isEqualTo(401);
    assertThat(post("/api/v1/policies", TestJwt.wholeBook(POLICY_READ), body).statusCode())
        .isEqualTo(403);
    assertThat(post("/api/v1/policies", TestJwt.branches(POLICY_WRITE, "B999"), body).statusCode())
        .isEqualTo(403);
    HttpResponse<String> ok =
        post("/api/v1/policies", TestJwt.agents(POLICY_WRITE, "AG0001"), body);
    assertThat(ok.statusCode()).isEqualTo(201);
    String pn = json.readTree(ok.body()).get("policyNumber").asText();
    // Underwriting a policy outside the entitlement looks like a missing policy.
    assertThat(
            post(
                    "/api/v1/policies/" + pn + "/underwriting-decisions",
                    TestJwt.branches(UNDERWRITING_DECIDE, "B999"),
                    "")
                .statusCode())
        .isEqualTo(404);
    assertThat(
            post(
                    "/api/v1/policies/POL-99999999/underwriting-decisions",
                    TestJwt.wholeBook(UNDERWRITING_DECIDE),
                    "")
                .statusCode())
        .isEqualTo(404);
    // Claims interface down: 503 and nothing written.
    HttpResponse<String> down =
        post(
            "/api/v1/policies/" + pn + "/underwriting-decisions",
            TestJwt.wholeBook(UNDERWRITING_DECIDE),
            "");
    assertThat(down.statusCode()).isEqualTo(503);
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM acmeins.underwriting_decisions WHERE policy_number = ?")
                .param(pn)
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void validationReportsTheLegacyMessageAndEveryViolation() throws Exception {
    String jwt = TestJwt.wholeBook(POLICY_WRITE);
    HttpResponse<String> r =
        post(
            "/api/v1/policies",
            jwt,
            "{\"policyType\":\" \",\"policyholderId\":\"\",\"effectiveDate\":\"2000-01-01\",\"coverageLimit\":0}");
    assertThat(r.statusCode()).isEqualTo(422);
    JsonNode e = json.readTree(r.body());
    assertThat(e.get("message").asText()).isEqualTo("POLICYHOLDER NOT FOUND IN SYSTEM");
    assertThat(e.get("violations").findValuesAsText("message"))
        .containsExactly(
            "POLICYHOLDER ID IS REQUIRED",
            "POLICY TYPE IS REQUIRED",
            "EFFECTIVE DATE CANNOT BE IN PAST",
            "POLICY LIMIT MUST BE GREATER THAN ZERO",
            "POLICYHOLDER NOT FOUND IN SYSTEM");
    holder("C525000003", 700, "S");
    HttpResponse<String> past =
        post(
            "/api/v1/policies",
            jwt,
            body("HOM", "C525000003", "AG0001", "B001", today().minusDays(1), "-5.00"));
    assertThat(json.readTree(past.body()).get("message").asText())
        .isEqualTo("POLICY LIMIT MUST BE GREATER THAN ZERO");
    HttpResponse<String> wide =
        post(
            "/api/v1/policies",
            jwt,
            body("HOMES", "C525000003", "AG0001", "B001", today(), "1.001"));
    assertThat(wide.statusCode()).isEqualTo(400);
    assertThat(json.readTree(wide.body()).get("violations")).hasSize(2);
  }
}
