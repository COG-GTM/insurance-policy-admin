package com.acme.insurance.pas.policyservice.bridge;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Lists and replays the bridge's dead-letter topic. Replay republishes the original event, value
 * and headers, to its original topic with a {@code replayOf} header; consumers deduplicate on
 * {@code eventId}, so replaying an event that also succeeded elsewhere is harmless. The DLT itself
 * is never modified; its retention (30 days, MSG-01) bounds what can be replayed.
 */
public class DeadLetterService {

  static final int MAX_SCAN = 10_000;

  /** A dead-lettered event; never the payload, which stays on the topic. */
  public record DeadLetter(
      String eventId,
      String originalTopic,
      int partition,
      long offset,
      Instant deadLetteredAt,
      String exception,
      String reason) {}

  private final Map<String, Object> consumerConfig;
  private final KafkaTemplate<String, String> producer;
  private final BridgeProperties properties;

  public DeadLetterService(
      Map<String, Object> consumerConfig,
      KafkaTemplate<String, String> producer,
      BridgeProperties properties) {
    this.consumerConfig = Map.copyOf(consumerConfig);
    this.producer = producer;
    this.properties = properties;
  }

  public List<DeadLetter> list() {
    return scan().stream().map(DeadLetterService::describe).toList();
  }

  /** Replays the dead letters with these event ids; returns the ids replayed. */
  public List<String> replay(Collection<String> eventIds) throws Exception {
    List<String> replayed = new ArrayList<>();
    for (ConsumerRecord<String, String> r : scan()) {
      String eventId = header(r, "eventId");
      String original = header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC);
      if (eventId == null || original == null || !eventIds.contains(eventId)) {
        continue;
      }
      if (replayed.contains(eventId)) {
        continue;
      }
      ProducerRecord<String, String> again = new ProducerRecord<>(original, r.key(), r.value());
      for (String name : new String[] {"eventId", "eventType", "schemaVersion"}) {
        String value = header(r, name);
        if (value != null) {
          again.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
      }
      again
          .headers()
          .add(
              "replayOf",
              (r.topic() + "/" + r.partition() + "/" + r.offset())
                  .getBytes(StandardCharsets.UTF_8));
      producer.send(again).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
      replayed.add(eventId);
    }
    return replayed;
  }

  private List<ConsumerRecord<String, String>> scan() {
    Map<String, Object> config = new HashMap<>(consumerConfig);
    config.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
    config.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
    config.remove(ConsumerConfig.GROUP_ID_CONFIG);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    List<ConsumerRecord<String, String>> records = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(properties.deadLetterTopic(), Duration.ofSeconds(10)).stream()
              .map(p -> new TopicPartition(p.topic(), p.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      while (records.size() < MAX_SCAN
          && partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
        consumer.poll(Duration.ofMillis(500)).forEach(records::add);
      }
    }
    return records;
  }

  private static DeadLetter describe(ConsumerRecord<String, String> r) {
    return new DeadLetter(
        header(r, "eventId"),
        header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC),
        r.partition(),
        r.offset(),
        Instant.ofEpochMilli(r.timestamp()),
        cause(r),
        bounded(header(r, KafkaHeaders.DLT_EXCEPTION_MESSAGE)));
  }

  private static String cause(ConsumerRecord<String, String> r) {
    String cause = header(r, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    return cause != null ? cause : header(r, KafkaHeaders.DLT_EXCEPTION_FQCN);
  }

  private static String bounded(String s) {
    return s == null || s.length() <= 300 ? s : s.substring(0, 300);
  }

  private static String header(ConsumerRecord<String, String> r, String name) {
    Header h = r.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }
}
