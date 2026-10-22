package com.acme.insurance.pas.policy.bridge;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pas.bridge")
public record BridgeProperties(
    String documentQueue,
    String decisionQueue,
    String referralQueue,
    List<String> referralTriggers,
    String charset) {
  public BridgeProperties {
    referralTriggers =
        referralTriggers == null ? List.of("NEW_BUSINESS") : List.copyOf(referralTriggers);
    charset = charset == null ? "IBM037" : charset;
  }
}
