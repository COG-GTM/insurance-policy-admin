package com.acme.insurance.pas.policy.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Kafka topic names from ADR 0001 D4 ({@code pas.events.*}). */
@ConfigurationProperties("pas.events")
public record PasEventProperties(String policyTopic, String underwritingTopic) {

  public PasEventProperties {
    if (policyTopic == null || policyTopic.isBlank()) {
      policyTopic = "pas.policy.events.v1";
    }
    if (underwritingTopic == null || underwritingTopic.isBlank()) {
      underwritingTopic = "pas.underwriting.events.v1";
    }
  }
}
