package com.acme.insurance.pas.policy.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class OutboxConfiguration {
  @Value("${pas.kafka.topics.partitions:1}")
  private int partitions;

  @Value("${pas.kafka.topics.replicas:1}")
  private int replicas;

  @Bean
  @ConditionalOnProperty(
      name = "pas.kafka.topics.enabled",
      havingValue = "true",
      matchIfMissing = true)
  NewTopic policyEventsTopic() {
    return TopicBuilder.name(OutboxWriter.POLICY_TOPIC)
        .partitions(partitions)
        .replicas(replicas)
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      name = "pas.kafka.topics.enabled",
      havingValue = "true",
      matchIfMissing = true)
  NewTopic underwritingEventsTopic() {
    return TopicBuilder.name(OutboxWriter.UNDERWRITING_TOPIC)
        .partitions(partitions)
        .replicas(replicas)
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      name = "pas.kafka.topics.enabled",
      havingValue = "true",
      matchIfMissing = true)
  NewTopic documentRequestsTopic() {
    return TopicBuilder.name(OutboxWriter.DOCUMENT_TOPIC)
        .partitions(partitions)
        .replicas(replicas)
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      name = "pas.kafka.topics.enabled",
      havingValue = "true",
      matchIfMissing = true)
  NewTopic documentBridgeDeadLetterTopic() {
    return deadLetterTopic(OutboxWriter.DOCUMENT_TOPIC);
  }

  @Bean
  @ConditionalOnProperty(
      name = "pas.kafka.topics.enabled",
      havingValue = "true",
      matchIfMissing = true)
  NewTopic underwritingBridgeDeadLetterTopic() {
    return deadLetterTopic(OutboxWriter.UNDERWRITING_TOPIC);
  }

  private NewTopic deadLetterTopic(String topic) {
    return TopicBuilder.name(
            EventConsumerGroups.dltTopic(topic, EventConsumerGroups.groupForTopic(topic)))
        .partitions(partitions)
        .replicas(replicas)
        .build();
  }
}
