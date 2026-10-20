package com.acme.insurance.pas.policy.integration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Captures messages in memory for test environments ({@code pas.integration.transport=memory}).
 * Operators read them at {@code GET /api/v1/admin/legacy-messages}. This is not FileNet.
 */
public class InMemoryLegacyQueueTransport implements LegacyQueueTransport {

  private final int capacity;
  private final Deque<LegacyQueueMessage> messages = new ArrayDeque<>();

  public InMemoryLegacyQueueTransport(int capacity) {
    this.capacity = capacity;
  }

  @Override
  public synchronized void send(LegacyQueueMessage message) {
    if (messages.size() == capacity) {
      messages.removeFirst();
    }
    messages.addLast(message);
  }

  /** Captured messages, oldest first. */
  public synchronized List<LegacyQueueMessage> messages() {
    return List.copyOf(messages);
  }

  public synchronized void clear() {
    messages.clear();
  }
}
