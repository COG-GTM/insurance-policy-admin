package com.acme.insurance.pas.policy.events;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class DltReplayService {
  private static final Logger LOGGER = LoggerFactory.getLogger(DltReplayService.class);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Set<String> REGISTERED_DLTS =
      Set.of(
          OutboxWriter.DOCUMENT_TOPIC + ".pas-filenet-document-bridge.dlt",
          OutboxWriter.UNDERWRITING_TOPIC + ".pas-uw-referral-bridge.dlt");

  private final ConsumerFactory<String, String> consumerFactory;
  private final KafkaTemplate<String, String> kafka;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Kafka clients are Spring-managed collaborators.")
  public DltReplayService(
      ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafka) {
    this.consumerFactory = consumerFactory;
    this.kafka = kafka;
  }

  public DltReplayResponse replay(DltReplayRequest request, String actor) {
    int maxRecords = request.maxRecords() == null ? 1000 : request.maxRecords();
    if (!REGISTERED_DLTS.contains(request.dltTopic())
        || request.partition() < 0
        || request.fromOffset() < 0
        || request.toOffset() < request.fromOffset()
        || maxRecords < 1
        || maxRecords > 1000
        || request.toOffset() - request.fromOffset() >= maxRecords) {
      throw new IllegalArgumentException("Invalid DLT replay range");
    }
    TopicPartition partition = new TopicPartition(request.dltTopic(), request.partition());
    List<Long> replayedOffsets = new ArrayList<>();
    String sourceTopic = null;
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("pas-dlt-replay", "manual-replay", null)) {
      consumer.assign(List.of(partition));
      consumer.seek(partition, request.fromOffset());
      while (System.nanoTime() < deadline && replayedOffsets.size() < maxRecords) {
        var records = consumer.poll(POLL_INTERVAL);
        boolean pastRange = false;
        for (ConsumerRecord<String, String> record : records.records(partition)) {
          if (record.offset() > request.toOffset()) {
            pastRange = true;
            break;
          }
          String topic = originalTopic(request.dltTopic(), record.headers());
          sourceTopic = topic;
          ProducerRecord<String, String> replay =
              new ProducerRecord<>(
                  topic, null, null, record.key(), record.value(), copyHeaders(record));
          replay
              .headers()
              .add(
                  new RecordHeader(
                      "x-replayed-from",
                      (request.dltTopic() + ":" + record.partition() + ":" + record.offset())
                          .getBytes(StandardCharsets.UTF_8)));
          kafka.send(replay).get(10, TimeUnit.SECONDS);
          replayedOffsets.add(record.offset());
        }
        if (pastRange || (!records.isEmpty() && records.records(partition).isEmpty())) {
          break;
        }
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("DLT replay failed", exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new IllegalStateException("DLT replay failed", exception);
    }
    long first = replayedOffsets.isEmpty() ? -1 : replayedOffsets.getFirst();
    long last = replayedOffsets.isEmpty() ? -1 : replayedOffsets.getLast();
    LOGGER.info("pas.events.dlt.replayed actor={} count={}", actor, replayedOffsets.size());
    return new DltReplayResponse(replayedOffsets.size(), first, last, sourceTopic);
  }

  private static String originalTopic(
      String dltTopic, org.apache.kafka.common.header.Headers headers) {
    Header original = headers.lastHeader("kafka_dlt-original-topic");
    if (original != null) {
      return new String(original.value(), StandardCharsets.UTF_8);
    }
    int suffix = dltTopic.lastIndexOf('.');
    int groupSuffix = dltTopic.lastIndexOf('.', suffix - 1);
    return groupSuffix > 0 ? dltTopic.substring(0, groupSuffix) : dltTopic;
  }

  private static Iterable<Header> copyHeaders(ConsumerRecord<String, String> record) {
    List<Header> headers = new ArrayList<>();
    record.headers().forEach(header -> headers.add(new RecordHeader(header.key(), header.value())));
    return headers;
  }
}
