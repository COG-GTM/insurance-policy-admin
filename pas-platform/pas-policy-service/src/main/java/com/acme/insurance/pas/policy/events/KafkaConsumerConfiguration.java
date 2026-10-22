package com.acme.insurance.pas.policy.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConsumerConfiguration {
  @Bean
  CommonErrorHandler kafkaErrorHandler(
      KafkaTemplate<Object, Object> template, MeterRegistry metrics) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, exception) -> {
              String group =
                  record.topic().equals(OutboxWriter.DOCUMENT_TOPIC)
                      ? "pas-filenet-document-bridge"
                      : "pas-uw-referral-bridge";
              return new org.apache.kafka.common.TopicPartition(
                  record.topic() + "." + group + ".dlt", record.partition());
            });
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2);
    backOff.setInitialInterval(500);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(5000);
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
            metrics.counter("pas.events.dlt", "topic", record.topic()).increment();
          }
        });
    return handler;
  }
}
