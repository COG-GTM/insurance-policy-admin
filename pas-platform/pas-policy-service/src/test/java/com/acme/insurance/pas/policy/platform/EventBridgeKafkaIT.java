package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import com.acme.insurance.pas.copybook.CopybookCharset;
import com.acme.insurance.pas.copybook.LegacyPolicyRecords;
import com.acme.insurance.pas.copybook.PolicyRecordCodec;
import com.acme.insurance.pas.policy.bridge.LegacyQueuePort;
import com.acme.insurance.pas.policy.events.CorrelationIds;
import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQJMSConnectionFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "pas.routing.new-business=new",
      "pas.routing.document-events=new",
      "pas.bridge.document-queue=TEST.PAS.DOCUMENT",
      "pas.bridge.decision-queue=TEST.PAS.DECISION",
      "pas.bridge.referral-queue=TEST.PAS.REFERRAL",
      "pas.bridge.charset=IBM037",
      "pas.renewal.window-days-before-expiry=3650",
      "pas.renewal.window-days-after-expiry=3650",
      "pas.outbox.relay.poll-interval=PT0.2S"
    })
@ActiveProfiles("it")
@Import(EventBridgeKafkaIT.ArtemisTestConfiguration.class)
class EventBridgeKafkaIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(
          DockerImageName.parse("mirror.gcr.io/apache/kafka:3.9.1")
              .asCompatibleSubstituteFor("apache/kafka"));

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PolicyRepository policies;
  @Autowired private KafkaTemplate<String, String> kafka;
  @Autowired private ConsumerFactory<String, String> consumerFactory;
  @Autowired private MeterRegistry metrics;

  @Autowired
  @Qualifier("artemisJmsTemplate")
  private JmsTemplate artemisJmsTemplate;

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("pas.kafka.topics.enabled", () -> true);
    registry.add("pas.outbox.relay.enabled", () -> true);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void publishesDocumentAndReferralRecordsThroughKafkaToArtemis() throws Exception {
    String policyNumber = createPolicy();
    Message document = receive("TEST.PAS.DOCUMENT");
    assertThat(document).isInstanceOf(BytesMessage.class);
    assertThat(document.getStringProperty("PasDocumentType")).isEqualTo("NEW_BUSINESS");
    assertThat(document.getStringProperty("PasEventId")).isNotBlank();
    assertThat(document.getStringProperty("PasPolicyVersion")).isEqualTo("1");
    assertThat(body(document)).isEqualTo(policyRecord(policyNumber));
    Message referral = receive("TEST.PAS.REFERRAL");
    assertThat(referral).isInstanceOf(BytesMessage.class);
    assertThat(referral.propertyExists("PasDocumentType")).isFalse();
    assertThat(new PolicyRecordCodec(CopybookCharset.EBCDIC).decode(body(referral)).policyNumber())
        .isEqualTo(policyNumber);
  }

  @Test
  void publishesUnderwritingDecisionRecordInIbm037() throws Exception {
    String policyNumber = createPolicy();
    receive("TEST.PAS.DOCUMENT");
    receive("TEST.PAS.REFERRAL");
    String underwriter =
        PolicyJwtTestSupport.mint(
            PasScopes.UNDERWRITING_DECIDE, "event-bridge-underwriter", Map.of("book", "ALL"));

    ResponseEntity<String> decision =
        post(
            "/api/v1/policies/" + policyNumber + "/underwriting-decisions",
            "",
            underwriter,
            "decision-" + UUID.randomUUID());

    assertThat(decision.getStatusCode().value()).isEqualTo(201);
    Message message = receive("TEST.PAS.DECISION");
    assertThat(message).isInstanceOf(BytesMessage.class);
    assertThat(message.propertyExists("PasDocumentType")).isFalse();
    assertThat(body(message))
        .isEqualTo(
            LegacyPolicyRecords.undwrtDecisionRecord(
                policies.findByPolicyNumber(policyNumber).orElseThrow(),
                CopybookCharset.fromName("IBM037")));
  }

  @Test
  void publishesRenewalAndEndorsementPolicyRecordsInIbm037() throws Exception {
    String policyNumber = createPolicy();
    receive("TEST.PAS.DOCUMENT");
    receive("TEST.PAS.REFERRAL");
    jdbc.update(
        "UPDATE pas.policies SET policy_status = 'AC' WHERE policy_number = ?", policyNumber);
    var policy = policies.findByPolicyNumber(policyNumber).orElseThrow();
    String lifecycleJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_RENEW + " " + PasScopes.POLICY_ENDORSE + " " + PasScopes.POLICY_READ,
            "event-bridge-lifecycle",
            Map.of("book", "ALL"));

    ResponseEntity<String> renewal =
        post(
            "/api/v1/policies/" + policyNumber + "/renewals",
            MAPPER.writeValueAsString(Map.of("termExpiryDate", policy.expiryDate().toString())),
            lifecycleJwt,
            "renewal-" + UUID.randomUUID());

    assertThat(renewal.getStatusCode().value()).isEqualTo(201);
    Message renewalMessage = receive("TEST.PAS.DOCUMENT");
    assertThat(renewalMessage.getStringProperty("PasDocumentType")).isEqualTo("RENEWAL");
    assertThat(body(renewalMessage)).isEqualTo(policyRecord(policyNumber));

    var renewedPolicy = policies.findByPolicyNumber(policyNumber).orElseThrow();
    ResponseEntity<String> endorsement =
        post(
            "/api/v1/policies/" + policyNumber + "/endorsements",
            MAPPER.writeValueAsString(
                Map.of("type", "CAN", "effectiveDate", renewedPolicy.effectiveDate().toString())),
            lifecycleJwt,
            "endorsement-" + UUID.randomUUID());

    assertThat(endorsement.getStatusCode().value()).isEqualTo(201);
    Message endorsementMessage = receive("TEST.PAS.DOCUMENT");
    assertThat(endorsementMessage.getStringProperty("PasDocumentType")).isEqualTo("ENDORSEMENT");
    assertThat(body(endorsementMessage)).isEqualTo(policyRecord(policyNumber));
  }

  @Test
  void dropsDuplicateAndStaleDocumentEventsWithoutSendingJmsMessages() throws Exception {
    String policyNumber = createPolicy();
    Message originalMessage = receive("TEST.PAS.DOCUMENT");
    receive("TEST.PAS.REFERRAL");
    String eventId = originalMessage.getStringProperty("PasEventId");
    String raw =
        jdbc.queryForObject(
            "SELECT payload::text FROM pas.outbox WHERE event_id = ?",
            String.class,
            UUID.fromString(eventId));
    double duplicatesBefore =
        counter(
            "pas.events.consumed",
            "topic",
            OutboxWriter.DOCUMENT_TOPIC,
            "group",
            "pas-filenet-document-bridge",
            "result",
            "duplicate");

    publishDocumentEvent(policyNumber, raw);
    await(
        () ->
            counter(
                    "pas.events.consumed",
                    "topic",
                    OutboxWriter.DOCUMENT_TOPIC,
                    "group",
                    "pas-filenet-document-bridge",
                    "result",
                    "duplicate")
                > duplicatesBefore);
    assertThat(receiveIfAvailable("TEST.PAS.DOCUMENT")).isNull();

    ObjectNode stale = (ObjectNode) MAPPER.readTree(raw);
    String staleEventId = UUID.randomUUID().toString();
    stale.put("eventId", staleEventId);
    double staleBefore =
        counter(
            "pas.events.consumed",
            "topic",
            OutboxWriter.DOCUMENT_TOPIC,
            "group",
            "pas-filenet-document-bridge",
            "result",
            "stale");
    publishDocumentEvent(policyNumber, MAPPER.writeValueAsString(stale));
    await(
        () ->
            counter(
                    "pas.events.consumed",
                    "topic",
                    OutboxWriter.DOCUMENT_TOPIC,
                    "group",
                    "pas-filenet-document-bridge",
                    "result",
                    "stale")
                > staleBefore);
    assertThat(consumedResult("pas-filenet-document-bridge", staleEventId)).isEqualTo("STALE");
    assertThat(receiveIfAvailable("TEST.PAS.DOCUMENT")).isNull();
  }

  @Test
  void deadLettersPoisonAndMissingPolicyEventsAndReplaysAfterPolicyInsert() throws Exception {
    String dltTopic = OutboxWriter.DOCUMENT_TOPIC + ".pas-filenet-document-bridge.dlt";
    double dltBefore = counter("pas.events.dlt", "topic", OutboxWriter.DOCUMENT_TOPIC);
    publishDocumentEvent("poison-" + UUID.randomUUID(), "{");
    ConsumerRecord<String, String> poison =
        receiveDltRecord(dltTopic, record -> "{".equals(record.value()));
    assertThat(poison.topic()).isEqualTo(dltTopic);
    await(() -> counter("pas.events.dlt", "topic", OutboxWriter.DOCUMENT_TOPIC) > dltBefore);

    String policyNumber = "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    String missingEventId = UUID.randomUUID().toString();
    String missingEvent = documentEvent(policyNumber, missingEventId, 1);
    double failuresBefore =
        counter(
            "pas.events.consumed",
            "topic",
            OutboxWriter.DOCUMENT_TOPIC,
            "group",
            "pas-filenet-document-bridge",
            "result",
            "failed");
    double missingDltBefore = counter("pas.events.dlt", "topic", OutboxWriter.DOCUMENT_TOPIC);
    publishDocumentEvent(policyNumber, missingEvent);
    ConsumerRecord<String, String> missing =
        receiveDltRecord(dltTopic, record -> record.value().contains(missingEventId));
    assertThat(missing.topic()).isEqualTo(dltTopic);
    await(
        () ->
            counter(
                    "pas.events.consumed",
                    "topic",
                    OutboxWriter.DOCUMENT_TOPIC,
                    "group",
                    "pas-filenet-document-bridge",
                    "result",
                    "failed")
                >= failuresBefore + 3);
    await(() -> counter("pas.events.dlt", "topic", OutboxWriter.DOCUMENT_TOPIC) > missingDltBefore);
    assertThat(receiveIfAvailable("TEST.PAS.DOCUMENT")).isNull();

    insertPolicy(policyNumber);
    String replayJwt =
        PolicyJwtTestSupport.mint(PasScopes.EVENTS_REPLAY, "event-replay", Map.of("book", "ALL"));
    String readOnlyJwt =
        PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, "event-read-only", Map.of("book", "ALL"));
    assertThat(
            replay(dltTopic, poison.partition(), poison.offset(), poison.offset(), 1, readOnlyJwt)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(replay("unknown.dlt", 0, 0, 0, 1, replayJwt).getStatusCode().value()).isEqualTo(400);
    assertThat(replay(dltTopic, 0, 10, 9, 2, replayJwt).getStatusCode().value()).isEqualTo(400);
    assertThat(replay(dltTopic, 0, 0, 2, 2, replayJwt).getStatusCode().value()).isEqualTo(400);

    TopicPartition sourcePartition = new TopicPartition(OutboxWriter.DOCUMENT_TOPIC, 0);
    try (Consumer<String, String> observer =
        consumerFactory.createConsumer("replay-observer-" + UUID.randomUUID())) {
      observer.assign(List.of(sourcePartition));
      observer.seekToEnd(List.of(sourcePartition));
      observer.poll(Duration.ofMillis(100));
      long sourceOffset = observer.position(sourcePartition);

      ResponseEntity<String> replayResponse =
          replay(dltTopic, missing.partition(), missing.offset(), missing.offset(), 1, replayJwt);

      assertThat(replayResponse.getStatusCode().value()).isEqualTo(200);
      assertThat(MAPPER.readTree(replayResponse.getBody()).path("replayedCount").asInt())
          .isEqualTo(1);
      ConsumerRecord<String, String> replayed =
          receiveSourceRecord(observer, sourcePartition, sourceOffset);
      Header replayHeader = replayed.headers().lastHeader("x-replayed-from");
      assertThat(replayHeader).isNotNull();
      assertThat(new String(replayHeader.value(), java.nio.charset.StandardCharsets.UTF_8))
          .isEqualTo(dltTopic + ":" + missing.partition() + ":" + missing.offset());
    }

    await(() -> "PROCESSED".equals(consumedResult("pas-filenet-document-bridge", missingEventId)));
    Message replayedMessage = receive("TEST.PAS.DOCUMENT");
    assertThat(replayedMessage.getStringProperty("PasEventId")).isEqualTo(missingEventId);
    assertThat(body(replayedMessage)).isEqualTo(policyRecord(policyNumber));
  }

  private String createPolicy() throws Exception {
    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    String agentJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_WRITE,
            "event-bridge-creator",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    String request =
        """
        {"policyType":"AUT","policyholderId":"%s","effectiveDate":"%s",
         "agentCode":"AGT001","branchCode":"B001","coverageLimit":100000.00,
         "deductible":500.00,"totalPremium":1200.00}
        """
            .formatted(custId, LocalDate.now().plusDays(1));
    ResponseEntity<String> created =
        post("/api/v1/policies", request, agentJwt, "bridge-" + UUID.randomUUID());
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(created.getHeaders().getFirst(CorrelationIds.HEADER_NAME)).isNotBlank();
    JsonNode policy = MAPPER.readTree(created.getBody());
    String policyNumber = policy.path("policyNumber").asText();

    return policyNumber;
  }

  private byte[] policyRecord(String policyNumber) {
    return new PolicyRecordCodec(CopybookCharset.fromName("IBM037"))
        .encode(policies.findByPolicyNumber(policyNumber).orElseThrow());
  }

  private void publishDocumentEvent(String key, String value) throws Exception {
    kafka.send(OutboxWriter.DOCUMENT_TOPIC, key, value).get(10, TimeUnit.SECONDS);
  }

  private String documentEvent(String policyNumber, String eventId, long version) throws Exception {
    return MAPPER.writeValueAsString(
        Map.of(
            "eventId",
            eventId,
            "eventType",
            "DocumentRequested",
            "schemaVersion",
            1,
            "policyNumber",
            policyNumber,
            "policyVersion",
            version,
            "occurredAt",
            OffsetDateTime.now().toString(),
            "correlationId",
            UUID.randomUUID().toString(),
            "data",
            Map.of(
                "documentType",
                "NEW_BUSINESS",
                "triggeringEventId",
                UUID.randomUUID().toString(),
                "triggeringEventType",
                "PolicyCreated")));
  }

  private ConsumerRecord<String, String> receiveDltRecord(
      String topic, java.util.function.Predicate<ConsumerRecord<String, String>> predicate) {
    TopicPartition partition = new TopicPartition(topic, 0);
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("dlt-observer-" + UUID.randomUUID())) {
      consumer.assign(List.of(partition));
      consumer.seekToBeginning(List.of(partition));
      long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record :
            consumer.poll(Duration.ofMillis(250)).records(partition)) {
          if (predicate.test(record)) {
            return record;
          }
        }
      }
    }
    throw new AssertionError("No matching DLT record on " + topic);
  }

  private ConsumerRecord<String, String> receiveSourceRecord(
      Consumer<String, String> consumer, TopicPartition partition, long offset) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      for (ConsumerRecord<String, String> record :
          consumer.poll(Duration.ofMillis(250)).records(partition)) {
        if (record.offset() >= offset) {
          return record;
        }
      }
    }
    throw new AssertionError("No replayed record on " + partition);
  }

  private ResponseEntity<String> replay(
      String topic, int partition, long from, long to, int max, String jwt) {
    return post(
        "/api/v1/admin/events/dlt-replay",
        """
        {"dltTopic":"%s","partition":%d,"fromOffset":%d,"toOffset":%d,"maxRecords":%d}
        """
            .formatted(topic, partition, from, to, max),
        jwt,
        "dlt-replay-" + UUID.randomUUID());
  }

  private String consumedResult(String group, String eventId) {
    return jdbc.query(
        "SELECT result FROM pas.consumed_events WHERE consumer_group = ? AND event_id = ?",
        rs -> rs.next() ? rs.getString(1) : null,
        group,
        UUID.fromString(eventId));
  }

  private void insertPolicy(String policyNumber) {
    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    LocalDate effectiveDate = LocalDate.now().minusDays(30);
    jdbc.update(
        """
        INSERT INTO pas.policies (
            policy_number, policy_type, policy_status, effective_date, expiry_date,
            policyholder_id, agent_code, branch_code, total_premium, deductible,
            coverage_limit, inception_date, renewal_count, uw_status, risk_score,
            web_indicator, api_flag, last_updated, updated_by, row_version
        ) VALUES (?, 'AUT', 'AC', ?, ?, ?, 'AGT001', 'B001', 1200, 500, 100000,
                  ?, 0, 'AP', 300, 'N', 'Y', now(), 'IT', 1)
        """,
        policyNumber,
        effectiveDate,
        effectiveDate.plusYears(1),
        custId,
        effectiveDate);
  }

  private double counter(String name, String... tags) {
    var counter = metrics.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  private void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
      Thread.sleep(50);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private Message receiveIfAvailable(String queue) {
    artemisJmsTemplate.setReceiveTimeout(500);
    return artemisJmsTemplate.receive(queue);
  }

  private Message receive(String queue) {
    artemisJmsTemplate.setReceiveTimeout(Duration.ofSeconds(30).toMillis());
    Message message = artemisJmsTemplate.receive(queue);
    assertThat(message).as("message on %s", queue).isNotNull();
    return message;
  }

  private static byte[] body(Message message) throws Exception {
    BytesMessage bytes = (BytesMessage) message;
    byte[] body = new byte[(int) bytes.getBodyLength()];
    bytes.readBytes(body);
    return body;
  }

  private ResponseEntity<String> post(String path, String body, String jwt, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", idempotencyKey);
    return restTemplate.exchange(
        "http://localhost:" + port + path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ArtemisTestConfiguration {
    @Bean(initMethod = "start", destroyMethod = "stop")
    ActiveMQServer embeddedArtemisServer() throws Exception {
      return ActiveMQServers.newActiveMQServer(
          new ConfigurationImpl()
              .setPersistenceEnabled(false)
              .setSecurityEnabled(false)
              .addAcceptorConfiguration("invm", "vm://0"));
    }

    @Bean(destroyMethod = "close")
    ActiveMQJMSConnectionFactory artemisConnectionFactory(ActiveMQServer embeddedArtemisServer) {
      return new ActiveMQJMSConnectionFactory("vm://0");
    }

    @Bean("artemisJmsTemplate")
    JmsTemplate artemisJmsTemplate(
        @Qualifier("artemisConnectionFactory") ConnectionFactory connectionFactory) {
      return new JmsTemplate(connectionFactory);
    }

    @Bean
    LegacyQueuePort artemisLegacyQueuePort(@Qualifier("artemisJmsTemplate") JmsTemplate template) {
      return (queueName, body, properties) ->
          template.send(
              queueName,
              session -> {
                BytesMessage message = session.createBytesMessage();
                message.writeBytes(body);
                for (Map.Entry<String, String> property : properties.entrySet()) {
                  message.setStringProperty(property.getKey(), property.getValue());
                }
                return message;
              });
    }
  }
}
