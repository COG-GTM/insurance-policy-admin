package com.acme.insurance.pas.policyservice.bridge;

import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.policyservice.events.EventsProperties;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the legacy bridge when {@code pas.bridge.enabled=true}: the listener, a retrying error
 * handler that sends what still fails to the dead-letter topic, and (local/test only) the bridge
 * topics. The byte-array producer stays private to the bridge, so Spring Boot's String {@code
 * KafkaTemplate} for the outbox relay is unaffected.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BridgeProperties.class)
@ConditionalOnProperty(name = "pas.bridge.enabled", havingValue = "true")
public class BridgeConfiguration {

  @Bean(destroyMethod = "destroy")
  DefaultKafkaProducerFactory<String, byte[]> bridgeProducerFactory(KafkaProperties kafka) {
    Map<String, Object> config = kafka.buildProducerProperties(null);
    return new DefaultKafkaProducerFactory<>(
        config, new StringSerializer(), new ByteArraySerializer());
  }

  @Bean
  LegacyRecordBridge legacyRecordBridge(
      DefaultKafkaProducerFactory<String, byte[]> bridgeProducerFactory,
      PolicyJdbcRepository policies,
      BridgeProperties properties) {
    return new LegacyRecordBridge(
        new BridgeRouter(properties, policies::findPolicy),
        new KafkaTemplate<>(bridgeProducerFactory),
        properties);
  }

  @Bean
  DeadLetterService deadLetterService(
      KafkaProperties kafka, KafkaTemplate<String, String> producer, BridgeProperties properties) {
    return new DeadLetterService(kafka, producer, properties);
  }

  @Bean
  CommonErrorHandler bridgeErrorHandler(
      KafkaTemplate<String, String> kafka, BridgeProperties properties) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafka, (record, e) -> new TopicPartition(properties.deadLetterTopic(), -1));
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            recoverer,
            new FixedBackOff(properties.retryInterval().toMillis(), properties.retries()));
    handler.addNotRetryableExceptions(UnbridgeableEventException.class);
    return handler;
  }

  @Bean
  @ConditionalOnProperty(name = "pas.events.create-topics", havingValue = "true")
  KafkaAdmin.NewTopics bridgeTopics(BridgeProperties properties, EventsProperties events) {
    return new KafkaAdmin.NewTopics(
        new NewTopic(properties.documentTriggerTopic(), events.partitions(), (short) 1),
        new NewTopic(properties.underwritingRequestTopic(), events.partitions(), (short) 1),
        new NewTopic(properties.underwritingDecisionTopic(), events.partitions(), (short) 1),
        new NewTopic(properties.deadLetterTopic(), events.partitions(), (short) 1));
  }
}
