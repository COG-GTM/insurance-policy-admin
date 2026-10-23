package com.acme.insurance.pas.policy.events;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class EventConsumerGroups {
  public static final String DOCUMENT_BRIDGE = "pas-filenet-document-bridge";
  public static final String REFERRAL_BRIDGE = "pas-uw-referral-bridge";
  public static final Map<String, String> GROUP_BY_TOPIC =
      Map.of(
          OutboxWriter.DOCUMENT_TOPIC, DOCUMENT_BRIDGE,
          OutboxWriter.UNDERWRITING_TOPIC, REFERRAL_BRIDGE);
  private static final Map<String, String> SOURCE_TOPIC_BY_DLT =
      GROUP_BY_TOPIC.entrySet().stream()
          .collect(
              Collectors.toUnmodifiableMap(
                  entry -> dltTopic(entry.getKey(), entry.getValue()), Map.Entry::getKey));

  private EventConsumerGroups() {}

  public static String groupForTopic(String topic) {
    String group = GROUP_BY_TOPIC.get(topic);
    if (group == null) {
      throw new IllegalArgumentException("No event consumer group registered for topic " + topic);
    }
    return group;
  }

  public static String dltTopic(String topic, String group) {
    return topic + "." + group + ".dlt";
  }

  public static Set<String> deadLetterTopics() {
    return new HashSet<>(SOURCE_TOPIC_BY_DLT.keySet());
  }

  public static String sourceTopicForDeadLetterTopic(String dltTopic) {
    String topic = SOURCE_TOPIC_BY_DLT.get(dltTopic);
    if (topic == null) {
      throw new IllegalArgumentException("No source topic registered for DLT " + dltTopic);
    }
    return topic;
  }
}
