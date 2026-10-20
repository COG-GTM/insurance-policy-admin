package com.acme.insurance.pas.policy.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/** Parks every record that lands on a PAS {@code .dlt} topic in {@code pas.dead_letters}. */
public class DeadLetterRecorder {

  public static final String CONSUMER = "pas-dead-letter-recorder";

  private final DeadLetterService deadLetters;

  public DeadLetterRecorder(DeadLetterService deadLetters) {
    this.deadLetters = deadLetters;
  }

  @KafkaListener(
      id = CONSUMER,
      groupId = CONSUMER,
      topics = {
        "${pas.events.document-topic}" + IntegrationConfiguration.DLT_SUFFIX,
        "${pas.events.policy-topic}" + IntegrationConfiguration.DLT_SUFFIX,
        "${pas.events.underwriting-topic}" + IntegrationConfiguration.DLT_SUFFIX
      },
      containerFactory = IntegrationConfiguration.DLT_FACTORY)
  public void onDeadLetter(ConsumerRecord<String, String> record) {
    deadLetters.record(record);
  }
}
