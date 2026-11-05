package com.acme.insurance.pas.policyservice.events;

import com.acme.insurance.pas.events.PasEventType;
import com.acme.insurance.pas.persistence.EventOutboxJdbcRepository;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Outbox relay and (local/test only) topic creation, see {@link EventsProperties}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventsProperties.class)
public class EventsConfiguration {

  @Bean
  @ConditionalOnProperty(name = "pas.events.relay-enabled", havingValue = "true")
  OutboxRelay outboxRelay(
      EventOutboxJdbcRepository outbox,
      KafkaTemplate<String, String> kafka,
      EventsProperties properties) {
    return new OutboxRelay(outbox, kafka, properties, Clock.systemDefaultZone());
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "pas.events.relay-enabled", havingValue = "true")
  @EnableScheduling
  static class RelayScheduling {}

  @Bean
  @ConditionalOnProperty(name = "pas.events.create-topics", havingValue = "true")
  KafkaAdmin.NewTopics pasTopics(EventsProperties properties) {
    NewTopic[] topics = new NewTopic[PasEventType.values().length];
    for (PasEventType type : PasEventType.values()) {
      topics[type.ordinal()] = new NewTopic(type.topic(), properties.partitions(), (short) 1);
    }
    return new KafkaAdmin.NewTopics(topics);
  }
}
