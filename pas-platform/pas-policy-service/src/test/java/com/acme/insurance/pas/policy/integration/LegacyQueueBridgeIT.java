package com.acme.insurance.pas.policy.integration;

import static com.acme.insurance.pas.policy.WriteApiSupport.createBody;
import static com.acme.insurance.pas.policy.WriteApiSupport.send;
import static com.acme.insurance.pas.policy.WriteApiSupport.today;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.copybook.PasCopybooks;
import com.acme.insurance.pas.common.domain.PolicyRecord;
import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.policy.WriteApiSupport;
import com.acme.insurance.pas.policy.events.PasEventTypes;
import com.acme.insurance.pas.policy.events.PolicyEndorsedData;
import com.acme.insurance.pas.policy.events.PolicyEventPublisher;
import com.acme.insurance.pas.policy.events.PolicyRenewedData;
import com.acme.insurance.pas.policy.persistence.CopybookMapping;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.persistence.PolicyRepository;
import com.acme.insurance.pas.policy.security.PasScopes;
import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * AB-528 acceptance in a test environment (Testcontainers PostgreSQL + Kafka, in-memory transport;
 * this is not FileNet): create → renew → endorse each produce a FileNet document message built from
 * the exact policy version, the underwriting queues get theirs, and a failing event goes to the
 * {@code .dlt} topic, is parked, replayed by an operator and processed exactly once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LegacyQueueBridgeIT {

  private static final String FILENET_QUEUE = "TEST.FILENET.DOCUMENT.TRIGGER";
  private static final String DOCUMENT_TOPIC = "pas.document.requests.v1";
  private static final Duration WAIT = Duration.ofSeconds(90);

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
    TestJwtIssuer.register(registry);
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.kafka.consumer.properties.metadata.max.age.ms", () -> "1000");
    registry.add("pas.outbox.relay.poll-interval", () -> "PT0.2S");
    registry.add("pas.claims.adapter", () -> "stub");
    registry.add("pas.integration.bridges-enabled", () -> "true");
    registry.add("pas.integration.transport", () -> "memory");
    registry.add("pas.integration.filenet-queue", () -> FILENET_QUEUE);
    registry.add("pas.integration.max-retries", () -> "2");
    registry.add("pas.integration.retry-initial-interval", () -> "PT0.1S");
    registry.add("pas.integration.retry-max-interval", () -> "PT0.2S");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PolicyHolderRepository holders;
  @Autowired private PolicyRepository policies;
  @Autowired private PolicyEventPublisher publisher;
  @Autowired private TransactionTemplate tx;
  @Autowired private InMemoryLegacyQueueTransport transport;
  @Autowired private ObjectMapper mapper;

  @Test
  void newRenewedAndEndorsedPoliciesStillProduceDocumentMessages() throws Exception {
    WriteApiSupport.holder(holders, "CUSTDOC001", 500, "S");
    String bearer =
        TestJwtIssuer.bearer()
            .scopes(PasScopes.POLICY_WRITE, PasScopes.UNDERWRITING_DECIDE)
            .agent("AG0001")
            .sign();
    ResponseEntity<String> created =
        send(
            rest,
            HttpMethod.POST,
            "/api/v1/policies",
            bearer,
            createBody("CUSTDOC001", "AUT", today(), "250000.00", "AG0001"),
            Map.of("X-Correlation-Id", "doc-corr-1"));
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    String number = mapper.readTree(created.getBody()).at("/policy/policyNumber").asText();
    PolicyRecord v0 = CopybookMapping.toRecord(policies.findById(number).orElseThrow());

    ResponseEntity<String> decided =
        send(
            rest,
            HttpMethod.POST,
            "/api/v1/policies/" + number + "/underwriting-decisions",
            bearer,
            null,
            Map.of());
    assertThat(decided.getStatusCode().value()).isEqualTo(201);
    PolicyRecord v1 = CopybookMapping.toRecord(policies.findById(number).orElseThrow());

    // AB-526 owns the renewal/endorsement services; this drives the PolicyEventPublisher contract
    // they call, after the same kind of committed policy mutation.
    PolicyRecord v2 =
        tx.execute(
            s -> {
              PolicyEntity p = policies.findById(number).orElseThrow();
              LocalDate previousExpiry = p.getExpiryDate();
              BigDecimal previousPremium = p.getTotalPremium();
              p.setEffectiveDate(previousExpiry);
              p.setExpiryDate(previousExpiry.plusYears(1));
              p.setRenewalCount((short) (p.getRenewalCount() + 1));
              p.setPolicyStatus("AC");
              p.setUwStatus("PN");
              p.setTotalPremium(BigDecimal.valueOf(132000, 2));
              p.setUpdatedBy("POLRNW");
              p = policies.saveAndFlush(p);
              publisher.policyRenewed(
                  p,
                  new PolicyRenewedData(
                      p.getPolicyType(),
                      p.getPolicyStatus(),
                      p.getUwStatus(),
                      previousExpiry,
                      p.getEffectiveDate(),
                      p.getExpiryDate(),
                      p.getRenewalCount(),
                      previousPremium,
                      p.getTotalPremium(),
                      BigDecimal.TEN),
                  "doc-corr-renew");
              return CopybookMapping.toRecord(p);
            });
    PolicyRecord v3 =
        tx.execute(
            s -> {
              PolicyEntity p = policies.findById(number).orElseThrow();
              BigDecimal adjustment = BigDecimal.valueOf(5000, 2);
              p.setTotalPremium(p.getTotalPremium().add(adjustment));
              p.setUpdatedBy("POLEND");
              p = policies.saveAndFlush(p);
              publisher.policyEndorsed(
                  p,
                  new PolicyEndorsedData(
                      1,
                      "AD",
                      p.getEffectiveDate(),
                      adjustment,
                      p.getTotalPremium(),
                      p.getPolicyStatus(),
                      p.getEffectiveDate(),
                      p.getExpiryDate()),
                  "doc-corr-endorse");
              return CopybookMapping.toRecord(p);
            });

    List<LegacyQueueMessage> documents = await(() -> messages(FILENET_QUEUE, number), 3);
    assertThat(documents)
        .extracting(LegacyQueueMessage::sourceTransaction)
        .containsExactly("POLNEW", "POLRNW", "POLEND");
    assertThat(documents).extracting(LegacyQueueMessage::policyVersion).containsExactly(0L, 2L, 3L);
    assertThat(documents).allSatisfy(m -> assertThat(m.length()).isEqualTo(125));
    assertMessage(documents.get(0), LegacyMessageSource.POLNEW, v0);
    assertMessage(documents.get(1), LegacyMessageSource.POLRNW, v2);
    assertMessage(documents.get(2), LegacyMessageSource.POLEND, v3);
    // Exact version, not latest: the new-business document still has the original premium.
    assertThat(documents.get(0).payload())
        .isNotEqualTo(LegacyPolicyMessages.encode(LegacyMessageSource.POLNEW, v3));
    // EBCDIC on the wire: 'P' of the policy number is 0xD7 in IBM037.
    assertThat(documents.get(0).payload()[0]).isEqualTo((byte) 0xD7);
    String renewedExpiry =
        new String(
            Arrays.copyOfRange(documents.get(1).payload(), 25, 33), PasCopybooks.HOST_CHARSET);
    assertThat(renewedExpiry).isEqualTo(v2.expiryDate().toString().replace("-", ""));

    List<LegacyQueueMessage> uwRequests =
        await(() -> messages("ACME.PAS.UNDERWRITING.REQUEST", number), 1);
    assertMessage(uwRequests.get(0), LegacyMessageSource.POLNEW, v0);
    List<LegacyQueueMessage> uwDecisions =
        await(() -> messages("ACME.PAS.UNDERWRITING.DECISION", number), 1);
    assertMessage(uwDecisions.get(0), LegacyMessageSource.UNDWRT, v1);
    assertThat(uwDecisions.get(0).policyVersion()).isEqualTo(1L);
  }

  @Test
  void failingEventIsDeadLetteredReplayedAndProcessedOnce() throws Exception {
    WriteApiSupport.holder(holders, "CUSTDLT001", 500, "S");
    WriteApiSupport.policy(
        jdbc, "POLDLT000001", "CUSTDLT001", "AG0001", "B001", "AC", "1000.00", "100000.00");
    UUID eventId = UUID.randomUUID();
    // policyVersion 7 has no snapshot yet: the bridge retries, then dead-letters the event.
    String envelope =
        """
        {"eventId":"%s","eventType":"DocumentRequested","schemaVersion":1,\
        "occurredAt":"2026-01-01T00:00:00Z","policyNumber":"POLDLT000001","policyVersion":7,\
        "correlationId":"dlt-corr","data":{"documentTrigger":"NEW_BUSINESS",\
        "sourceTransaction":"POLNEW","triggeringEventId":"%s",\
        "triggeringEventType":"PolicyCreated"}}"""
            .formatted(eventId, UUID.randomUUID());
    String poison = "{not json";
    try (KafkaProducer<String, String> producer = producer()) {
      ProducerRecord<String, String> record =
          new ProducerRecord<>(DOCUMENT_TOPIC, "POLDLT000001", envelope);
      record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
      record
          .headers()
          .add("eventType", PasEventTypes.DOCUMENT_REQUESTED.getBytes(StandardCharsets.UTF_8));
      producer.send(record).get();
      producer.send(new ProducerRecord<>(DOCUMENT_TOPIC, "POLDLT000002", poison)).get();
    }

    String operator = TestJwtIssuer.bearer().scopes(PasScopes.BATCH_OPERATE).sign();
    JsonNode parked = awaitDeadLetter(operator, "POLDLT000001");
    assertThat(parked.get("status").asText()).isEqualTo("PARKED");
    assertThat(parked.get("dltTopic").asText()).isEqualTo(DOCUMENT_TOPIC + ".dlt");
    assertThat(parked.get("originalTopic").asText()).isEqualTo(DOCUMENT_TOPIC);
    assertThat(parked.get("eventId").asText()).isEqualTo(eventId.toString());
    assertThat(parked.get("errorClass").asText()).endsWith("PolicyVersionNotFoundException");
    JsonNode poisoned = awaitDeadLetter(operator, "POLDLT000002");
    assertThat(poisoned.get("errorClass").asText()).endsWith("PoisonMessageException");
    assertThat(messages(FILENET_QUEUE, "POLDLT000001")).isEmpty();

    long id = parked.get("deadLetterId").asLong();
    String replayPath = "/api/v1/admin/dead-letters/" + id + "/replay";
    assertThat(
            send(rest, HttpMethod.POST, replayPath, null, null, Map.of()).getStatusCode().value())
        .isEqualTo(401);
    String reader = TestJwtIssuer.bearer().scopes(PasScopes.POLICY_READ).sign();
    assertThat(
            send(rest, HttpMethod.POST, replayPath, reader, null, Map.of()).getStatusCode().value())
        .isEqualTo(403);

    // The operator fixes the cause (the missing version now exists), then replays.
    jdbc.update(
        "INSERT INTO pas.policy_versions (policy_number, policy_version, policy_type,"
            + " policy_status, effective_date, expiry_date, policyholder_id, agent_code,"
            + " branch_code, total_premium, deductible, coverage_limit, inception_date,"
            + " renewal_count, uw_status, risk_score, web_indicator, api_flag, last_updated,"
            + " updated_by) SELECT trim(policy_number), 7, policy_type, policy_status,"
            + " effective_date, expiry_date, policyholder_id, agent_code, branch_code,"
            + " total_premium, deductible, coverage_limit, inception_date, renewal_count,"
            + " uw_status, risk_score, web_indicator, api_flag, last_updated, updated_by"
            + " FROM pas.policies WHERE policy_number = 'POLDLT000001'");
    ResponseEntity<String> replayed =
        send(rest, HttpMethod.POST, replayPath, operator, null, Map.of());
    assertThat(replayed.getStatusCode().value()).isEqualTo(200);
    assertThat(mapper.readTree(replayed.getBody()).get("status").asText()).isEqualTo("REPLAYED");
    List<LegacyQueueMessage> sent = await(() -> messages(FILENET_QUEUE, "POLDLT000001"), 1);
    assertThat(sent.get(0).eventId()).isEqualTo(eventId.toString());

    // A second replay is absorbed by the eventId idempotency check.
    assertThat(
            send(rest, HttpMethod.POST, replayPath, operator, null, Map.of())
                .getStatusCode()
                .value())
        .isEqualTo(200);
    Thread.sleep(Duration.ofSeconds(3).toMillis());
    assertThat(messages(FILENET_QUEUE, "POLDLT000001")).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.processed_events WHERE event_id = ?",
                Integer.class,
                eventId))
        .isEqualTo(1);
    ResponseEntity<String> after =
        send(rest, HttpMethod.GET, "/api/v1/admin/dead-letters/" + id, operator, null, Map.of());
    assertThat(mapper.readTree(after.getBody()).get("replayCount").asInt()).isEqualTo(2);
  }

  private void assertMessage(
      LegacyQueueMessage message, LegacyMessageSource source, PolicyRecord atVersion) {
    assertThat(message.payload()).isEqualTo(LegacyPolicyMessages.encode(source, atVersion));
  }

  private List<LegacyQueueMessage> messages(String queue, String policyNumber) {
    return transport.messages().stream()
        .filter(m -> m.queue().equals(queue) && m.policyNumber().equals(policyNumber))
        .toList();
  }

  private static List<LegacyQueueMessage> await(
      Supplier<List<LegacyQueueMessage>> query, int expected) throws InterruptedException {
    Instant deadline = Instant.now().plus(WAIT);
    List<LegacyQueueMessage> found = query.get();
    while (found.size() < expected && Instant.now().isBefore(deadline)) {
      Thread.sleep(200);
      found = query.get();
    }
    assertThat(found).hasSize(expected);
    return found;
  }

  private JsonNode awaitDeadLetter(String bearer, String key) throws Exception {
    Instant deadline = Instant.now().plus(WAIT);
    while (Instant.now().isBefore(deadline)) {
      ResponseEntity<String> res =
          send(rest, HttpMethod.GET, "/api/v1/admin/dead-letters", bearer, null, Map.of());
      assertThat(res.getStatusCode().value()).isEqualTo(200);
      for (JsonNode n : mapper.readTree(res.getBody())) {
        if (key.equals(n.path("messageKey").asText())) {
          return n;
        }
      }
      Thread.sleep(200);
    }
    throw new AssertionError("no dead letter for " + key);
  }

  private static KafkaProducer<String, String> producer() {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    return new KafkaProducer<>(props);
  }
}
