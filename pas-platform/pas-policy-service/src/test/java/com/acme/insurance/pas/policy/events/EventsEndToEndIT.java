package com.acme.insurance.pas.policy.events;

import static com.acme.insurance.pas.policy.WriteApiSupport.createBody;
import static com.acme.insurance.pas.policy.WriteApiSupport.send;
import static com.acme.insurance.pas.policy.WriteApiSupport.today;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.policy.WriteApiSupport;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.security.PasScopes;
import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * AB-525 acceptance: create-policy and underwriting run end to end, the outbox relay publishes to
 * Kafka, and an independent subscriber sees the events with the ADR envelope.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EventsEndToEndIT {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
    TestJwtIssuer.register(registry);
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("pas.outbox.relay.poll-interval", () -> "PT0.2S");
    registry.add("pas.claims.adapter", () -> "stub");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PolicyHolderRepository holders;
  @Autowired private ObjectMapper mapper;

  @Test
  void createAndUnderwriteArePublishedToSubscribers() throws Exception {
    WriteApiSupport.holder(holders, "CUSTEV0001", 500, "S");
    try (KafkaConsumer<String, String> consumer = subscriber()) {
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
              createBody("CUSTEV0001", "COM", today(), "250000.00", "AG0001"),
              Map.of("X-Correlation-Id", "e2e-corr-1"));
      assertThat(created.getStatusCode().value()).isEqualTo(201);
      String number = mapper.readTree(created.getBody()).at("/policy/policyNumber").asText();
      ResponseEntity<String> decided =
          send(
              rest,
              HttpMethod.POST,
              "/api/v1/policies/" + number + "/underwriting-decisions",
              bearer,
              null,
              Map.of("X-Correlation-Id", "e2e-corr-2"));
      assertThat(decided.getStatusCode().value()).isEqualTo(201);

      List<ConsumerRecord<String, String>> records = consume(consumer, number, 2);
      ConsumerRecord<String, String> createdRecord = records.get(0);
      assertThat(createdRecord.topic()).isEqualTo("pas.policy.events.v1");
      assertThat(createdRecord.key()).isEqualTo(number);
      assertThat(header(createdRecord, "eventType")).isEqualTo("PolicyCreated");
      JsonNode createdEvent = mapper.readTree(createdRecord.value());
      assertThat(createdEvent.get("eventType").asText()).isEqualTo("PolicyCreated");
      assertThat(UUID.fromString(createdEvent.get("eventId").asText())).isNotNull();
      assertThat(header(createdRecord, "eventId")).isEqualTo(createdEvent.get("eventId").asText());
      assertThat(createdEvent.get("schemaVersion").asInt()).isEqualTo(1);
      assertThat(Instant.parse(createdEvent.get("occurredAt").asText())).isNotNull();
      assertThat(createdEvent.get("policyNumber").asText()).isEqualTo(number);
      assertThat(createdEvent.get("policyVersion").asLong()).isZero();
      assertThat(createdEvent.get("correlationId").asText()).isEqualTo("e2e-corr-1");
      assertThat(createdEvent.at("/data/coverages")).hasSize(3);
      assertThat(createdRecord.value()).doesNotContain("Tester").doesNotContain("Pat");

      ConsumerRecord<String, String> uwRecord = records.get(1);
      assertThat(uwRecord.topic()).isEqualTo("pas.underwriting.events.v1");
      JsonNode uwEvent = mapper.readTree(uwRecord.value());
      // credit 500 standard: (900 - 500) / 2 = 200 -> AP
      assertThat(uwEvent.get("eventType").asText()).isEqualTo("UnderwritingDecided");
      assertThat(uwEvent.get("policyVersion").asLong()).isEqualTo(1);
      assertThat(uwEvent.get("correlationId").asText()).isEqualTo("e2e-corr-2");
      assertThat(uwEvent.at("/data/decisionCode").asText()).isEqualTo("AP");
      assertThat(uwEvent.at("/data/riskScore").asInt()).isEqualTo(200);

      awaitPublished(number);
    }
  }

  private void awaitPublished(String number) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(30);
    Integer pending = null;
    while (Instant.now().isBefore(deadline)) {
      pending =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM pas.outbox WHERE message_key = ? AND published_at IS NULL",
              Integer.class,
              number);
      if (pending != null && pending == 0) {
        return;
      }
      Thread.sleep(200);
    }
    assertThat(pending).as("unpublished outbox rows").isZero();
  }

  private static KafkaConsumer<String, String> subscriber() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "500");
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of("pas.policy.events.v1", "pas.underwriting.events.v1"));
    return consumer;
  }

  private static List<ConsumerRecord<String, String>> consume(
      KafkaConsumer<String, String> consumer, String key, int expected) {
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    Instant deadline = Instant.now().plusSeconds(60);
    while (out.size() < expected && Instant.now().isBefore(deadline)) {
      for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
        if (key.equals(r.key())) {
          out.add(r);
        }
      }
    }
    assertThat(out).as("events for " + key).hasSize(expected);
    out.sort((a, b) -> a.topic().equals("pas.policy.events.v1") ? -1 : 1);
    return out;
  }

  private static String header(ConsumerRecord<String, String> r, String name) {
    return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }
}
