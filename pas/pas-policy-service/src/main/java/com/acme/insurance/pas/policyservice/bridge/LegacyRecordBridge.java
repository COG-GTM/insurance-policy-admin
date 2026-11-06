package com.acme.insurance.pas.policyservice.bridge;

import com.acme.insurance.pas.events.PasEventType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Consumes every PAS domain event and publishes the legacy message for it (see {@link
 * BridgeRouter}). At-least-once: a redelivered event is bridged again with the same {@code eventId}
 * header, which the MQ sink copies to a JMS property so consumers can drop duplicates (Q-528-2). It
 * also reads the bridge-only retry topic, where a dead-letter replay puts the event with its domain
 * topic in the {@link #ORIGINAL_TOPIC} header.
 */
public class LegacyRecordBridge implements DisposableBean {

  public static final String[] TOPICS =
      Arrays.stream(PasEventType.values()).map(PasEventType::topic).toArray(String[]::new);

  /** Domain topic of an event on the retry topic. */
  public static final String ORIGINAL_TOPIC = "originalTopic";

  private final BridgeRouter router;
  private final KafkaTemplate<String, byte[]> bytes;
  private final BridgeProperties properties;

  public LegacyRecordBridge(
      BridgeRouter router, KafkaTemplate<String, byte[]> bytes, BridgeProperties properties) {
    this.router = router;
    this.bytes = bytes;
    this.properties = properties;
  }

  @KafkaListener(
      id = "pas-legacy-bridge",
      groupId = "${pas.bridge.group-id:pas-legacy-bridge}",
      topics = {
        "#{T(com.acme.insurance.pas.policyservice.bridge.LegacyRecordBridge).TOPICS}",
        "${pas.bridge.retry-topic:pas.bridge.retry}"
      },
      properties = {"auto.offset.reset=earliest"})
  public void onEvent(ConsumerRecord<String, String> event)
      throws InterruptedException, ExecutionException, TimeoutException {
    String topic = event.topic();
    if (topic.equals(properties.retryTopic())) {
      Header original = event.headers().lastHeader(ORIGINAL_TOPIC);
      if (original == null) {
        throw new UnbridgeableEventException("no " + ORIGINAL_TOPIC + " header on " + topic);
      }
      topic = new String(original.value(), StandardCharsets.UTF_8);
    }
    for (BridgeMessage m : router.route(topic, event.value())) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(m.topic(), m.key(), m.value());
      for (Map.Entry<String, String> h : m.headers().entrySet()) {
        record.headers().add(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8));
      }
      bytes.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void destroy() throws Exception {
    if (bytes.getProducerFactory() instanceof DisposableBean producers) {
      producers.destroy();
    }
  }
}
