package com.acme.insurance.pas.policy.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@EnableConfigurationProperties({ConsumerRetryProperties.class, EventReplayProperties.class})
public class KafkaConsumerConfiguration {
  @Bean
  CommonErrorHandler kafkaErrorHandler(
      KafkaTemplate<Object, Object> template,
      MeterRegistry metrics,
      ConsumerRetryProperties retryProperties) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, exception) -> {
              String group = EventConsumerGroups.groupForTopic(record.topic());
              return new org.apache.kafka.common.TopicPartition(
                  EventConsumerGroups.dltTopic(record.topic(), group), record.partition());
            });
    ExponentialBackOffWithMaxRetries backOff =
        new ExponentialBackOffWithMaxRetries(retryProperties.maxRetries());
    backOff.setInitialInterval(retryProperties.initialInterval().toMillis());
    backOff.setMultiplier(retryProperties.multiplier());
    backOff.setMaxInterval(retryProperties.maxInterval().toMillis());
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(PoisonEventException.class);
    handler.setRetryListeners(
        new RetryListener() {
          @Override
          public void failedDelivery(
              org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
              Exception exception,
              int deliveryAttempt) {}

          @Override
          public void recovered(
              org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Exception exception) {
            String group = EventConsumerGroups.groupForTopic(record.topic());
            metrics.counter("pas.events.dlt", "topic", record.topic(), "group", group).increment();
          }
        });
    return handler;
  }
}
