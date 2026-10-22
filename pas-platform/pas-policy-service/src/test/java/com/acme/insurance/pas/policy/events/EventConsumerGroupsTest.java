package com.acme.insurance.pas.policy.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class EventConsumerGroupsTest {
  @Test
  void registersConsumerGroupsAndDeadLetterTopicsTogether() {
    assertThat(EventConsumerGroups.groupForTopic(OutboxWriter.DOCUMENT_TOPIC))
        .isEqualTo(EventConsumerGroups.DOCUMENT_BRIDGE);
    assertThat(EventConsumerGroups.groupForTopic(OutboxWriter.UNDERWRITING_TOPIC))
        .isEqualTo(EventConsumerGroups.REFERRAL_BRIDGE);
    assertThat(EventConsumerGroups.deadLetterTopics())
        .containsExactlyInAnyOrder(
            EventConsumerGroups.dltTopic(
                OutboxWriter.DOCUMENT_TOPIC, EventConsumerGroups.DOCUMENT_BRIDGE),
            EventConsumerGroups.dltTopic(
                OutboxWriter.UNDERWRITING_TOPIC, EventConsumerGroups.REFERRAL_BRIDGE));
  }

  @Test
  void rejectsTopicsWithoutRegisteredConsumerGroups() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EventConsumerGroups.groupForTopic("unregistered"));
  }
}
