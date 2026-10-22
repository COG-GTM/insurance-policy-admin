package com.acme.insurance.pas.policy.events;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

  private final ConsumerFactory<String, String> consumerFactory;
  private final KafkaTemplate<String, String> kafka;
  private final EventReplayProperties replayProperties;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Kafka clients are Spring-managed collaborators.")
  public DltReplayService(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafka,
      EventReplayProperties replayProperties) {
    this.consumerFactory = consumerFactory;
    this.kafka = kafka;
    this.replayProperties = replayProperties;
  }

  public DltReplayResponse replay(DltReplayRequest request, String actor) {
    int maxRecords =
        request.maxRecords() == null ? replayProperties.maxRecords() : request.maxRecords();
    if (!EventConsumerGroups.deadLetterTopics().contains(request.dltTopic())
        || request.partition() < 0
        || request.fromOffset() < 0
        || request.toOffset() < request.fromOffset()
        || maxRecords < 1
        || maxRecords > replayProperties.maxRecords()
        || request.toOffset() - request.fromOffset() >= maxRecords) {
      throw new IllegalArgumentException("Invalid DLT replay range");
    }
    String sourceTopic = EventConsumerGroups.sourceTopicForDeadLetterTopic(request.dltTopic());
    TopicPartition partition = new TopicPartition(request.dltTopic(), request.partition());
    List<Long> replayedOffsets = new ArrayList<>();
    int skipped = 0;
    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("pas-dlt-replay", "manual-replay", null)) {
      consumer.assign(List.of(partition));
      long endOffset = consumer.endOffsets(List.of(partition)).get(partition);
      long requestedEndPosition =
          request.toOffset() == Long.MAX_VALUE ? Long.MAX_VALUE : request.toOffset() + 1;
      long stopPosition = Math.min(requestedEndPosition, endOffset);
      if (request.fromOffset() >= stopPosition) {
        LOGGER.info("pas.events.dlt.replayed actor={} count=0 skipped=0", actor);
        return new DltReplayResponse(0, 0, -1, -1, sourceTopic);
      }
      consumer.seek(partition, request.fromOffset());
      long deadline = System.nanoTime() + replayProperties.pollTimeout().toNanos();
      int processedRecords = 0;
      long lastProcessedOffset = -1;
      while (System.nanoTime() < deadline
          && processedRecords < maxRecords
          && consumer.position(partition) < stopPosition) {
        var records = consumer.poll(replayProperties.pollTimeout());
        for (ConsumerRecord<String, String> record : records.records(partition)) {
          if (record.offset() >= stopPosition) {
            break;
          }
          Header original = record.headers().lastHeader("kafka_dlt-original-topic");
          if (original != null
              && (original.value() == null
                  || !sourceTopic.equals(new String(original.value(), StandardCharsets.UTF_8)))) {
            LOGGER.warn(
                "Skipping DLT record with mismatched original topic dltTopic={} partition={} offset={}",
                request.dltTopic(),
                record.partition(),
                record.offset());
            skipped++;
          } else {
            ProducerRecord<String, String> replay =
                new ProducerRecord<>(
                    sourceTopic, null, null, record.key(), record.value(), copyHeaders(record));
            replay
                .headers()
                .add(
                    new RecordHeader(
                        "x-replayed-from",
                        (request.dltTopic() + ":" + record.partition() + ":" + record.offset())
                            .getBytes(StandardCharsets.UTF_8)));
            kafka
                .send(replay)
                .get(replayProperties.pollTimeout().toMillis(), TimeUnit.MILLISECONDS);
            replayedOffsets.add(record.offset());
          }
          lastProcessedOffset = record.offset();
          processedRecords++;
          if (lastProcessedOffset >= request.toOffset() || processedRecords >= maxRecords) {
            break;
          }
        }
        if (lastProcessedOffset >= request.toOffset()
            || processedRecords >= maxRecords
            || consumer.position(partition) >= stopPosition) {
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
    LOGGER.info(
        "pas.events.dlt.replayed actor={} count={} skipped={}",
        actor,
        replayedOffsets.size(),
        skipped);
    return new DltReplayResponse(replayedOffsets.size(), skipped, first, last, sourceTopic);
  }

  private static Iterable<Header> copyHeaders(ConsumerRecord<String, String> record) {
    List<Header> headers = new ArrayList<>();
    record.headers().forEach(header -> headers.add(new RecordHeader(header.key(), header.value())));
    return headers;
  }
}
