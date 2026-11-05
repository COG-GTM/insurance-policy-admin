package com.acme.insurance.pas.policyservice.bridge;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
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
 * is never modified; its retention (30 days, MSG-01) bounds what can be replayed. Listing pages by
 * partition offsets; replay searches the whole retained topic.
 */
public class DeadLetterService {

  private static final Pattern CURSOR = Pattern.compile("\\d{1,5}:\\d{1,18}(,\\d{1,5}:\\d{1,18})*");

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

  /** One page of dead letters; pass {@code nextAfter} as {@code after} for the next page. */
  public record DeadLetterPage(List<DeadLetter> deadLetters, String nextAfter) {
    public DeadLetterPage {
      deadLetters = List.copyOf(deadLetters);
    }
  }

  /** A cursor is {@code partition:offset} per partition, comma separated. */
  public static boolean isCursor(String cursor) {
    return cursor.length() <= 2_000 && CURSOR.matcher(cursor).matches();
  }

  /**
   * Up to {@code limit} dead letters, oldest first per partition, starting at {@code after} (from
   * the earliest retained offset when null).
   */
  public DeadLetterPage list(String after, int limit) {
    List<DeadLetter> page = new ArrayList<>();
    Walk walk =
        walk(
            after == null ? Map.of() : parse(after),
            r -> {
              if (page.size() >= limit) {
                return false;
              }
              page.add(describe(r));
              return true;
            });
    return new DeadLetterPage(page, walk.more() ? format(walk.next()) : null);
  }

  /**
   * Replays the dead letters with these event ids, searching the whole retained topic; returns the
   * ids replayed.
   */
  public List<String> replay(Collection<String> eventIds) throws Exception {
    Map<String, ConsumerRecord<String, String>> found = new LinkedHashMap<>();
    walk(
        Map.of(),
        r -> {
          String eventId = header(r, "eventId");
          if (eventId != null
              && eventIds.contains(eventId)
              && header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC) != null) {
            found.putIfAbsent(eventId, r);
          }
          return found.size() < eventIds.size();
        });
    for (ConsumerRecord<String, String> r : found.values()) {
      ProducerRecord<String, String> again =
          new ProducerRecord<>(header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC), r.key(), r.value());
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
    }
    return List.copyOf(found.keySet());
  }

  private record Walk(Map<Integer, Long> next, boolean more) {}

  /**
   * Visits the DLT from {@code start} (earliest offset for partitions not in it) up to the end
   * offsets at call time, until the visitor returns false. Returns the next unvisited offset per
   * partition and whether records remain.
   */
  private Walk walk(Map<Integer, Long> start, Predicate<ConsumerRecord<String, String>> visitor) {
    Map<String, Object> config = new HashMap<>(consumerConfig);
    config.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
    config.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
    config.remove(ConsumerConfig.GROUP_ID_CONFIG);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    Map<Integer, Long> next = new TreeMap<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(properties.deadLetterTopic(), Duration.ofSeconds(10)).stream()
              .map(p -> new TopicPartition(p.topic(), p.partition()))
              .toList();
      consumer.assign(partitions);
      Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      for (TopicPartition p : partitions) {
        long from = Math.max(begin.get(p), start.getOrDefault(p.partition(), 0L));
        consumer.seek(p, from);
        next.put(p.partition(), from);
      }
      boolean stopped = false;
      while (!stopped && partitions.stream().anyMatch(p -> next.get(p.partition()) < end.get(p))) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          TopicPartition p = new TopicPartition(r.topic(), r.partition());
          if (r.offset() < next.get(r.partition()) || r.offset() >= end.get(p)) {
            continue;
          }
          if (!visitor.test(r)) {
            stopped = true;
            break;
          }
          next.put(r.partition(), r.offset() + 1);
        }
        if (!stopped) {
          for (TopicPartition p : partitions) {
            next.merge(p.partition(), consumer.position(p), Math::max);
          }
        }
      }
      boolean more = partitions.stream().anyMatch(p -> next.get(p.partition()) < end.get(p));
      return new Walk(next, more);
    }
  }

  private static Map<Integer, Long> parse(String cursor) {
    Map<Integer, Long> positions = new HashMap<>();
    for (String part : cursor.split(",")) {
      String[] po = part.split(":");
      positions.put(Integer.parseInt(po[0]), Long.parseLong(po[1]));
    }
    return positions;
  }

  private static String format(Map<Integer, Long> positions) {
    StringBuilder cursor = new StringBuilder();
    positions.forEach(
        (p, o) -> cursor.append(cursor.isEmpty() ? "" : ",").append(p).append(':').append(o));
    return cursor.toString();
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
