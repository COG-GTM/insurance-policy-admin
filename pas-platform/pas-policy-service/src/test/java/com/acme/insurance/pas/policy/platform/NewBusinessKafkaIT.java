package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class NewBusinessKafkaIT {
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
  void publishesPolicyCreatedAndReferralDecisionEventsWithSchemaEnvelope() throws Exception {
    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    String agentJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_WRITE,
            "kafka-creator",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    String createBody =
        """
        {"policyType":"AUT","policyholderId":"%s","effectiveDate":"%s",
         "agentCode":"AGT001","branchCode":"B001","coverageLimit":100000.00,
         "deductible":500.00,"totalPremium":1200.00}
        """
            .formatted(custId, LocalDate.now().plusDays(1));
    String groupId = "ab525-" + UUID.randomUUID();
    Properties consumerProperties = new Properties();
    consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProperties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
      consumer.subscribe(List.of("pas.policy.events.v1", "pas.underwriting.events.v1"));
      ResponseEntity<String> created =
          post("/api/v1/policies", createBody, agentJwt, "kafka-" + UUID.randomUUID());
      assertThat(created.getStatusCode().value()).isEqualTo(201);
      String policyNumber = MAPPER.readTree(created.getBody()).path("policyNumber").asText();
      String underwriterJwt =
          PolicyJwtTestSupport.mint(
              PasScopes.UNDERWRITING_DECIDE,
              "kafka-underwriter",
              Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
      ResponseEntity<String> decision =
          post(
              "/api/v1/policies/" + policyNumber + "/underwriting-decisions",
              "",
              underwriterJwt,
              null);
      assertThat(decision.getStatusCode().value()).isEqualTo(201);
      assertThat(MAPPER.readTree(decision.getBody()).path("decisionCode").asText()).isEqualTo("RS");

      Set<String> eventTypes = new HashSet<>();
      boolean createdEventHasSchemaEnvelope = false;
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline
          && !(eventTypes.contains("PolicyCreated")
              && eventTypes.contains("UnderwritingDecisionRecorded")
              && eventTypes.contains("UnderwritingReferralRequested"))) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          JsonNode envelope = MAPPER.readTree(record.value());
          if (!policyNumber.equals(record.key())) {
            continue;
          }
          assertThat(envelope.path("policyNumber").asText()).isEqualTo(record.key());
          assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(1);
          assertThat(envelope.path("eventId").asText()).isNotBlank();
          assertThat(envelope.path("occurredAt").asText()).isNotBlank();
          assertThat(envelope.path("eventType").asText()).isNotBlank();
          assertThat(envelope.path("policyVersion").asLong()).isPositive();
          assertThat(envelope.path("data").isObject()).isTrue();
          String eventType = envelope.path("eventType").asText();
          assertValidSchema(eventType, envelope);
          eventTypes.add(eventType);
          if ("PolicyCreated".equals(eventType)) {
            assertThat(record.topic()).isEqualTo("pas.policy.events.v1");
            assertThat(envelope.path("policyVersion").asLong()).isEqualTo(1L);
            assertThat(record.value()).doesNotContain(custId);
            createdEventHasSchemaEnvelope = true;
          }
          if ("UnderwritingDecisionRecorded".equals(eventType)
              || "UnderwritingReferralRequested".equals(eventType)) {
            assertThat(record.topic()).isEqualTo("pas.underwriting.events.v1");
          }
        }
      }
      assertThat(eventTypes)
          .contains(
              "PolicyCreated", "UnderwritingDecisionRecorded", "UnderwritingReferralRequested");
      assertThat(createdEventHasSchemaEnvelope).isTrue();
    }
  }

  private void assertValidSchema(String eventType, JsonNode envelope) {
    var schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(Path.of("../events/schemas/v1", eventType + ".schema.json").toUri());
    assertThat(schema.validate(envelope)).isEmpty();
  }

  private ResponseEntity<String> post(String path, String body, String jwt, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt);
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return restTemplate.exchange(
        "http://localhost:" + port + path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }
}
