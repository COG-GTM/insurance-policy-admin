package com.acme.insurance.pas.policyservice.bridge;

import static com.acme.insurance.pas.policyservice.security.PasScopes.EVENTS_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.EVENTS_REPLAY;
import static com.acme.insurance.pas.policyservice.security.PasScopes.OPS_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_ENDORSE;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_RENEW;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.copybook.PolicyRecordCodec;
import com.acme.insurance.pas.events.PasEvent;
import com.acme.insurance.pas.events.PasEvents;
import com.acme.insurance.pas.persistence.EventOutboxJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.policyservice.TestJwt;
import com.acme.insurance.pas.policyservice.TestcontainersConfiguration;
import com.acme.insurance.pas.policyservice.events.OutboxWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * AB-528 acceptance in a test environment: new, renewed and endorsed policies each produce the
 * FileNet document trigger (the 125-byte POLICY-RECORD the MQ sink delivers unchanged), new
 * business also reaches the underwriting request queue topic, an event the bridge cannot process
 * lands on the dead-letter topic and is replayed through the ops API, and a parked outbox row is
 * replayed through the same API.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "pas.events.relay-enabled=true",
      "pas.events.create-topics=true",
      "pas.events.relay-interval=PT0.2S",
      "pas.bridge.enabled=true",
      "pas.bridge.retries=1",
      "pas.bridge.retry-interval=PT0.1S",
      "pas.business-zone=UTC"
    })
@Import(TestcontainersConfiguration.class)
@Testcontainers
class MessagingBridgeIT {

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  private static final AtomicInteger NEXT =
      new AtomicInteger(java.util.concurrent.ThreadLocalRandom.current().nextInt(90_000));
  private static final String DOCS = "pas.bridge.filenet.document-trigger";
  private static final String UW_REQUEST = "pas.bridge.mq.underwriting-request";

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();
  private final PasEvents events = new PasEvents(Clock.systemUTC());

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired OutboxWriter outbox;
  @Autowired EventOutboxJdbcRepository outboxRows;
  @Autowired PolicyJdbcRepository policies;
  @Autowired TransactionTemplate tx;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  private String nextNumber() {
    return String.format("POL-528%05d", NEXT.incrementAndGet());
  }

  private void insertPolicy(String number, LocalDate effective, LocalDate expiry) {
    jdbc.sql("DELETE FROM acmeins.endorsements WHERE policy_number = ?").param(number).update();
    jdbc.sql("DELETE FROM acmeins.coverages WHERE policy_number = ?").param(number).update();
    jdbc.sql("DELETE FROM acmeins.policies WHERE policy_number = ?").param(number).update();
    jdbc.sql(
            """
            INSERT INTO acmeins.policies (policy_number, policy_type, policy_status, effective_date,
                expiry_date, policyholder_id, agent_code, branch_code, total_premium,
                renewal_count, uw_status)
            VALUES (?, 'AUT', 'AC', ?, ?, 'C000000001', 'AG5281', 'BR58', ?, 1, 'AP')
            """)
        .params(number, effective, expiry, new BigDecimal("1000.00"))
        .update();
    jdbc.sql(
            """
            INSERT INTO acmeins.coverages (policy_number, sequence_num, coverage_type,
                premium, effective_date, expiry_date, status)
            VALUES (?, 1, 'BI', 10.00, ?, ?, 'AC')
            """)
        .params(number, effective, expiry)
        .update();
  }

  private void deletePolicy(String number) {
    jdbc.sql("DELETE FROM acmeins.coverages WHERE policy_number = ?").param(number).update();
    jdbc.sql("DELETE FROM acmeins.policies WHERE policy_number = ?").param(number).update();
  }

  private HttpResponse<String> send(String method, String path, String body, String jwt)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + jwt)
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String header(ConsumerRecord<String, byte[]> r, String name) {
    var h = r.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  /** Bridge messages for one policy, read the way the MQ sink reads them: raw bytes. */
  private List<ConsumerRecord<String, byte[]>> bridged(String policyNumber, int expected) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + System.nanoTime());
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(DOCS, UW_REQUEST, "pas.bridge.mq.underwriting-decision"));
      long deadline = System.currentTimeMillis() + 45_000;
      while (found.size() < expected && System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
          if (policyNumber.equals(r.key())) {
            found.add(r);
          }
        }
      }
    }
    return found;
  }

  /** Records on {@code topic} with this {@code eventId} header, read to the current end. */
  private long countOn(String topic, String eventId) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(i -> new TopicPartition(topic, i.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      long count = 0;
      long deadline = System.currentTimeMillis() + 30_000;
      while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))
          && System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
          if (eventId.equals(header(r, "eventId"))) {
            count++;
          }
        }
      }
      return count;
    }
  }

  private void assertDocumentTrigger(
      ConsumerRecord<String, byte[]> r, String policyNumber, String documentType) {
    assertThat(r.topic()).isEqualTo(DOCS);
    assertThat(header(r, "documentType")).isEqualTo(documentType);
    assertThat(header(r, "eventId")).matches("[0-9a-f-]{36}");
    assertThat(r.value()).hasSize(125);
    Policy decoded = new PolicyRecordCodec().decode(r.value());
    assertThat(decoded.policyNumber().trim()).isEqualTo(policyNumber);
  }

  @Test
  void newRenewedAndEndorsedPoliciesTriggerDocuments() throws Exception {
    // New business through POST /api/v1/policies (POLNEW).
    jdbc.sql("DELETE FROM acmeins.policy_holders WHERE cust_id = 'C528000001'").update();
    jdbc.sql(
            "INSERT INTO acmeins.policy_holders (cust_id, cust_type, last_name, credit_score,"
                + " risk_tier) VALUES ('C528000001', 'I', 'Bridgeholder', 720, 'S')")
        .update();
    String writer =
        TestJwt.sign(
            TestJwt.claims(POLICY_WRITE + " " + POLICY_READ)
                .claim("pas_branches", List.of("BR58"))
                .build());
    HttpResponse<String> created =
        send(
            "POST",
            "/api/v1/policies",
            "{\"policyType\":\"AUT\",\"policyholderId\":\"C528000001\",\"agentCode\":\"AG5281\","
                + "\"branchCode\":\"BR58\",\"effectiveDate\":\""
                + today()
                + "\",\"totalPremium\":900.00,\"deductible\":250.00,\"coverageLimit\":50000.00}",
            writer);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    String newPolicy = json.readTree(created.body()).get("policyNumber").asText();
    List<ConsumerRecord<String, byte[]>> nb = bridged(newPolicy, 2);
    assertThat(nb).extracting(ConsumerRecord::topic).containsExactlyInAnyOrder(DOCS, UW_REQUEST);
    assertDocumentTrigger(
        nb.stream().filter(r -> r.topic().equals(DOCS)).findFirst().orElseThrow(),
        newPolicy,
        BridgeRouter.DOCUMENT_NEW);
    assertThat(new String(nb.get(0).value(), StandardCharsets.ISO_8859_1))
        .doesNotContain("Bridgeholder");

    // Renewal (POLRNW).
    String renewing = nextNumber();
    insertPolicy(renewing, LocalDate.of(2024, 3, 1), LocalDate.of(2025, 3, 1));
    HttpResponse<String> renewed =
        send(
            "POST",
            "/api/v1/policies/" + renewing + "/renewals",
            "",
            TestJwt.agents(POLICY_RENEW, "AG5281"));
    assertThat(renewed.statusCode()).as(renewed.body()).isEqualTo(200);
    List<ConsumerRecord<String, byte[]>> rn = bridged(renewing, 1);
    assertThat(rn).hasSize(1);
    assertDocumentTrigger(rn.get(0), renewing, BridgeRouter.DOCUMENT_RENEWAL);
    assertThat(new PolicyRecordCodec().decode(rn.get(0).value()).expiryDate())
        .isEqualTo(LocalDate.of(2026, 3, 1));

    // Endorsement (POLEND).
    String endorsing = nextNumber();
    insertPolicy(endorsing, today().minusDays(100), today().plusDays(265));
    HttpResponse<String> endorsed =
        send(
            "POST",
            "/api/v1/policies/" + endorsing + "/endorsements",
            "{\"endorsementType\":\"ACH\",\"description\":\"address change\"}",
            TestJwt.agents(POLICY_ENDORSE, "AG5281"));
    assertThat(endorsed.statusCode()).as(endorsed.body()).isEqualTo(201);
    List<ConsumerRecord<String, byte[]>> en = bridged(endorsing, 1);
    assertThat(en).hasSize(1);
    assertDocumentTrigger(en.get(0), endorsing, BridgeRouter.DOCUMENT_ENDORSEMENT);
  }

  @Test
  void anUnbridgeableEventIsDeadLetteredAndReplayedThroughTheOpsApi() throws Exception {
    String number = nextNumber();
    insertPolicy(number, today().minusDays(10), today().plusDays(355));
    Policy policy = policies.findPolicy(number).orElseThrow();
    List<Coverage> coverages = policies.findCoverages(number);
    deletePolicy(number);
    PasEvent event = events.policyCreated(policy, coverages, "it-dlt");
    outbox.append(event);

    String ops = TestJwt.wholeBook(EVENTS_READ);
    assertThat(
            send("GET", "/api/v1/ops/events/dead-letters", null, TestJwt.wholeBook(OPS_READ))
                .statusCode())
        .isEqualTo(403);
    JsonNode deadLetter = null;
    long deadline = System.currentTimeMillis() + 45_000;
    while (deadLetter == null && System.currentTimeMillis() < deadline) {
      HttpResponse<String> list = send("GET", "/api/v1/ops/events/dead-letters", null, ops);
      assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
      for (JsonNode d : json.readTree(list.body()).get("deadLetters")) {
        if (event.eventId().toString().equals(d.get("eventId").asText())) {
          deadLetter = d;
        }
      }
      Thread.sleep(500);
    }
    assertThat(deadLetter).as("dead letter for " + event.eventId()).isNotNull();
    assertThat(deadLetter.get("originalTopic").asText()).isEqualTo("pas.policy.created");
    assertThat(deadLetter.get("exception").asText()).endsWith("UnbridgeableEventException");
    assertThat(deadLetter.toString()).doesNotContain("C000000001");

    String body = "{\"eventIds\":[\"" + event.eventId() + "\"]}";
    assertThat(send("POST", "/api/v1/ops/events/dead-letters/replay", body, ops).statusCode())
        .isEqualTo(403);
    long domainEventsBefore = countOn("pas.policy.created", event.eventId().toString());

    insertPolicy(number, today().minusDays(10), today().plusDays(355));
    HttpResponse<String> replay =
        send(
            "POST",
            "/api/v1/ops/events/dead-letters/replay",
            body,
            TestJwt.wholeBook(EVENTS_REPLAY));
    assertThat(replay.statusCode()).as(replay.body()).isEqualTo(202);
    assertThat(json.readTree(replay.body()).get("replayed").get(0).asText())
        .isEqualTo(event.eventId().toString());
    List<ConsumerRecord<String, byte[]>> docs = bridged(number, 1);
    assertThat(docs).hasSize(1);
    assertDocumentTrigger(docs.get(0), number, BridgeRouter.DOCUMENT_NEW);
    assertThat(header(docs.get(0), "eventId")).isEqualTo(event.eventId().toString());
    assertThat(countOn("pas.bridge.retry", event.eventId().toString())).isEqualTo(1);
    assertThat(countOn("pas.policy.created", event.eventId().toString()))
        .as("native subscribers of the domain topic do not see the replay")
        .isEqualTo(domainEventsBefore);
  }

  @Test
  void aParkedOutboxEventIsListedAndReplayed() throws Exception {
    String number = nextNumber();
    insertPolicy(number, today().minusDays(10), today().plusDays(355));
    Policy policy = policies.findPolicy(number).orElseThrow();
    PasEvent event = events.policyCreated(policy, policies.findCoverages(number), "it-park");
    String eventId = event.eventId().toString();
    tx.executeWithoutResult(
        s -> {
          outbox.append(event);
          outboxRows.park(
              outboxRows.findByEventId(eventId).orElseThrow().outboxSeq(), LocalDateTime.now());
        });

    HttpResponse<String> parked =
        send("GET", "/api/v1/ops/events/outbox/parked", null, TestJwt.wholeBook(EVENTS_READ));
    assertThat(parked.statusCode()).isEqualTo(200);
    assertThat(parked.body()).contains(eventId).doesNotContain("C000000001");
    assertThat(
            send(
                    "GET",
                    "/api/v1/ops/events/outbox/parked?limit=0",
                    null,
                    TestJwt.wholeBook(EVENTS_READ))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "GET",
                    "/api/v1/ops/events/dead-letters?after=x",
                    null,
                    TestJwt.wholeBook(EVENTS_READ))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "/api/v1/ops/events/dead-letters/replay",
                    "{\"eventIds\":[null]}",
                    TestJwt.wholeBook(EVENTS_REPLAY))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "/api/v1/ops/events/outbox/" + eventId + "/replay",
                    null,
                    TestJwt.wholeBook(EVENTS_READ))
                .statusCode())
        .isEqualTo(403);
    assertThat(
            send(
                    "POST",
                    "/api/v1/ops/events/outbox/" + eventId + "/replay",
                    null,
                    TestJwt.wholeBook(EVENTS_REPLAY))
                .statusCode())
        .isEqualTo(202);
    assertThat(
            send(
                    "POST",
                    "/api/v1/ops/events/outbox/00000000-0000-0000-0000-000000000000/replay",
                    null,
                    TestJwt.wholeBook(EVENTS_REPLAY))
                .statusCode())
        .isEqualTo(404);

    List<ConsumerRecord<String, byte[]>> docs = bridged(number, 1);
    assertThat(docs).hasSize(1);
    assertDocumentTrigger(docs.get(0), number, BridgeRouter.DOCUMENT_NEW);
  }
}
