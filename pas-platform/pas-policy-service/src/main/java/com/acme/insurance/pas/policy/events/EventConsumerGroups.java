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
  private static final Set<String> DEAD_LETTER_TOPICS =
      GROUP_BY_TOPIC.entrySet().stream()
          .map(entry -> dltTopic(entry.getKey(), entry.getValue()))
          .collect(Collectors.toUnmodifiableSet());

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
    return new HashSet<>(DEAD_LETTER_TOPICS);
  }
}
