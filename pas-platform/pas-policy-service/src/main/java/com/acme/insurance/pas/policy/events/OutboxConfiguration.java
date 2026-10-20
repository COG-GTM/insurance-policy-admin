package com.acme.insurance.pas.policy.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/** Outbox wiring: topic/relay properties, the relay (unless disabled) and backlog gauges. */
@Configuration
@EnableConfigurationProperties({PasEventProperties.class, OutboxRelayProperties.class})
public class OutboxConfiguration {

  @Bean
  OutboxMetrics outboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
    OutboxMetrics metrics = new OutboxMetrics(jdbc);
    Gauge.builder("pas.outbox.pending", metrics, OutboxMetrics::pending)
        .description("Outbox events not yet published to Kafka")
        .register(registry);
    Gauge.builder("pas.outbox.oldest.pending.age", metrics, OutboxMetrics::oldestPendingAgeSeconds)
        .description("Age of the oldest unpublished outbox event")
        .baseUnit("seconds")
        .register(registry);
    return metrics;
  }

  /** The scheduled relay; {@code pas.outbox.relay.enabled=false} turns it off (e.g. tests). */
  @Configuration
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "pas.outbox.relay",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class RelayConfiguration {

    @Bean
    OutboxRelay outboxRelay(
        JdbcTemplate jdbc,
        PlatformTransactionManager transactionManager,
        KafkaTemplate<String, String> kafka,
        OutboxRelayProperties properties) {
      return new OutboxRelay(jdbc, transactionManager, kafka, properties);
    }
  }
}
