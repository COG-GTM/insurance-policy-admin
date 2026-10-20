package com.acme.insurance.pas.policy.support;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation id for a request: the caller's {@code X-Correlation-Id} when it is a safe token,
 * otherwise a new UUID. Events carry it as {@code correlationId}; errors return it as {@code
 * traceId}.
 */
public final class CorrelationIds {

  public static final String HEADER = "X-Correlation-Id";
  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

  private CorrelationIds() {}

  public static String resolve(String header) {
    if (header != null && SAFE.matcher(header).matches()) {
      return header;
    }
    return UUID.randomUUID().toString();
  }
}
