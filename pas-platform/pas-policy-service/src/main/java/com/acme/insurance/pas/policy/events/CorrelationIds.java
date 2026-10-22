package com.acme.insurance.pas.policy.events;

import java.util.UUID;
import java.util.regex.Pattern;

public final class CorrelationIds {
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private CorrelationIds() {}

  public static String fromHeader(String value) {
    return value != null && VALID.matcher(value).matches() ? value : UUID.randomUUID().toString();
  }
}
