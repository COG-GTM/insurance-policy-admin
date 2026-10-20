package com.acme.insurance.pas.policy.integration;

import com.acme.insurance.pas.policy.events.PolicyVersionSnapshots;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Legacy queue bridges, dead letters and replay (AB-528). Dead-letter storage and the replay API
 * are always available; the Kafka consumers only start with {@code
 * pas.integration.bridges-enabled=true} (a route flag per ADR 0001, off until the FileNet and
 * Underwriting cut-over is agreed).
 */
@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationConfiguration {

  public static final String DLT_SUFFIX = ".dlt";
  public static final String BRIDGE_FACTORY = "pasBridgeListenerFactory";
  public static final String DLT_FACTORY = "pasDeadLetterListenerFactory";
  private static final long DLT_RECORDER_RETRY_MS = 1000L;

  @Bean
  LegacyQueueTransport legacyQueueTransport(IntegrationProperties properties) {
    return IntegrationProperties.TRANSPORT_MEMORY.equals(properties.transport())
        ? new InMemoryLegacyQueueTransport(properties.memoryCapacity())
        : new LoggingLegacyQueueTransport();
  }

  @Bean
  ProcessedEvents processedEvents(JdbcTemplate jdbc) {
    return new ProcessedEvents(jdbc);
  }

  @Bean
  DeadLetterService deadLetterService(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
    return new DeadLetterService(jdbc, kafka);
  }

  @Configuration
  @EnableKafka
  @ConditionalOnProperty(prefix = "pas.integration", name = "bridges-enabled", havingValue = "true")
  static class Bridges {

    /** Retry with exponential backoff, then publish to {@code <topic>.dlt}. */
    @Bean
    DefaultErrorHandler pasBridgeErrorHandler(
        KafkaTemplate<String, String> kafka, IntegrationProperties properties) {
      DeadLetterPublishingRecoverer recoverer =
          new DeadLetterPublishingRecoverer(
              kafka, (record, e) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
      ExponentialBackOffWithMaxRetries backOff =
          new ExponentialBackOffWithMaxRetries(properties.maxRetries());
      backOff.setInitialInterval(properties.retryInitialInterval().toMillis());
      backOff.setMultiplier(properties.retryMultiplier());
      backOff.setMaxInterval(properties.retryMaxInterval().toMillis());
      DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
      handler.addNotRetryableExceptions(PoisonMessageException.class);
      return handler;
    }

    @Bean(BRIDGE_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> pasBridgeListenerFactory(
        ConsumerFactory<String, String> consumerFactory,
        DefaultErrorHandler pasBridgeErrorHandler) {
      ConcurrentKafkaListenerContainerFactory<String, String> f =
          new ConcurrentKafkaListenerContainerFactory<>();
      f.setConsumerFactory(consumerFactory);
      f.setCommonErrorHandler(pasBridgeErrorHandler);
      return f;
    }

    /** The recorder never drops a dead letter: it retries until the database accepts it. */
    @Bean(DLT_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> pasDeadLetterListenerFactory(
        ConsumerFactory<String, String> consumerFactory) {
      ConcurrentKafkaListenerContainerFactory<String, String> f =
          new ConcurrentKafkaListenerContainerFactory<>();
      f.setConsumerFactory(consumerFactory);
      f.setCommonErrorHandler(
          new DefaultErrorHandler(
              new FixedBackOff(DLT_RECORDER_RETRY_MS, FixedBackOff.UNLIMITED_ATTEMPTS)));
      return f;
    }

    @Bean
    LegacyMessageHandler legacyMessageHandler(
        ProcessedEvents processed,
        PolicyVersionSnapshots snapshots,
        LegacyQueueTransport transport,
        Clock clock) {
      return new LegacyMessageHandler(processed, snapshots, transport, clock);
    }

    @Bean
    LegacyQueueBridge legacyQueueBridge(
        ObjectMapper mapper, LegacyMessageHandler handler, IntegrationProperties properties) {
      return new LegacyQueueBridge(mapper, handler, properties);
    }

    @Bean
    DeadLetterRecorder deadLetterRecorder(DeadLetterService deadLetters) {
      return new DeadLetterRecorder(deadLetters);
    }
  }
}
