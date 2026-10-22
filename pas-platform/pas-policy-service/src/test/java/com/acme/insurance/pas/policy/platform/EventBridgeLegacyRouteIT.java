package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.bridge.EventBridgeListeners;
import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.ConnectionFactory;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.jms.client.ActiveMQJMSConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
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
      "pas.routing.document-events=legacy",
      "pas.bridge.document-queue=TEST.PAS.DOCUMENT",
      "pas.bridge.charset=IBM037",
      "pas.outbox.relay.enabled=false"
    })
@ActiveProfiles("it")
@Import(EventBridgeLegacyRouteIT.ArtemisTestConfiguration.class)
class EventBridgeLegacyRouteIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(
          DockerImageName.parse("mirror.gcr.io/apache/kafka:3.9.1")
              .asCompatibleSubstituteFor("apache/kafka"));

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private ApplicationContext applicationContext;
  @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
  @Autowired private KafkaTemplate<String, String> kafka;

  @Autowired
  @Qualifier("artemisJmsTemplate")
  private JmsTemplate artemisJmsTemplate;

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("pas.kafka.topics.enabled", () -> true);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void legacyDocumentRouteDoesNotStartListenersOrSendJmsMessages() throws Exception {
    assertThat(applicationContext.getBeansOfType(EventBridgeListeners.class)).isEmpty();
    assertThat(listenerRegistry.getListenerContainers()).isEmpty();

    String event =
        MAPPER.writeValueAsString(
            Map.of(
                "eventId",
                UUID.randomUUID().toString(),
                "eventType",
                "DocumentRequested",
                "schemaVersion",
                1,
                "policyNumber",
                "MISSING00001",
                "policyVersion",
                1,
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
    kafka.send(OutboxWriter.DOCUMENT_TOPIC, "MISSING00001", event).get(10, TimeUnit.SECONDS);

    artemisJmsTemplate.setReceiveTimeout(Duration.ofSeconds(1).toMillis());
    assertThat(artemisJmsTemplate.receive("TEST.PAS.DOCUMENT")).isNull();
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
  }
}
