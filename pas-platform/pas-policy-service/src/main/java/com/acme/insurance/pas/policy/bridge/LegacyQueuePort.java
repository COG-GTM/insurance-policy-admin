package com.acme.insurance.pas.policy.bridge;

import java.util.Map;

public interface LegacyQueuePort {
  void put(String queueName, byte[] body, Map<String, String> properties);
}
