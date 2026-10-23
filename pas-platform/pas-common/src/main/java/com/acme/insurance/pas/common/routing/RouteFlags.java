package com.acme.insurance.pas.common.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RouteFlags {
  public static final String POLICY_INQUIRY = "policy-inquiry";
  public static final String NEW_BUSINESS = "new-business";
  public static final String UNDERWRITING = "underwriting";
  public static final String RENEWAL = "renewal";
  public static final String ENDORSEMENT = "endorsement";
  public static final String PREMIUM_BATCH = "premium-batch";
  public static final String DAILY_EXTRACT = "daily-extract";
  public static final String MONTHLY_EXPOSURE = "monthly-exposure";
  public static final String DOCUMENT_EVENTS = "document-events";
  public static final String BROKER_PORTAL_READ = "broker-portal-read";
  private static final List<String> CAPABILITY_ORDER =
      List.of(
          POLICY_INQUIRY,
          NEW_BUSINESS,
          UNDERWRITING,
          RENEWAL,
          ENDORSEMENT,
          PREMIUM_BATCH,
          DAILY_EXTRACT,
          MONTHLY_EXPOSURE,
          DOCUMENT_EVENTS,
          BROKER_PORTAL_READ);
  public static final Set<String> CAPABILITIES =
      Collections.unmodifiableSet(new LinkedHashSet<>(CAPABILITY_ORDER));

  private final Map<String, RouteState> states;

  private RouteFlags(Map<String, RouteState> states) {
    this.states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
  }

  public static RouteFlags of(Map<String, String> configured) {
    Map<String, String> values = normalizeCapabilities(configured);
    Set<String> invalid = new LinkedHashSet<>(values.keySet());
    invalid.removeAll(CAPABILITIES);
    if (!invalid.isEmpty()) {
      throw new IllegalArgumentException("Unknown route capability: " + invalid.iterator().next());
    }
    Map<String, RouteState> states = new LinkedHashMap<>();
    for (String capability : CAPABILITY_ORDER) {
      String configuredState = values.get(capability);
      try {
        states.put(
            capability,
            configuredState == null ? RouteState.LEGACY : RouteState.parse(configuredState));
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(
            "Invalid route flag for " + capability + ": " + configuredState, exception);
      }
    }
    return new RouteFlags(states);
  }

  private static Map<String, String> normalizeCapabilities(Map<String, String> configured) {
    Map<String, String> values = new LinkedHashMap<>();
    if (configured == null) {
      return values;
    }
    configured.forEach(
        (capability, state) -> {
          String hyphenated = capability.replace('.', '-');
          String normalized = CAPABILITIES.contains(hyphenated) ? hyphenated : capability;
          if (values.containsKey(normalized) && !Objects.equals(values.get(normalized), state)) {
            throw new IllegalArgumentException("Conflicting route flags for " + normalized);
          }
          values.put(normalized, state);
        });
    return values;
  }

  public RouteState state(String capability) {
    if (capability == null) {
      throw new IllegalArgumentException("Unknown route capability: null");
    }
    RouteState state = states.get(capability.toLowerCase(Locale.ROOT));
    if (state == null) {
      throw new IllegalArgumentException("Unknown route capability: " + capability);
    }
    return state;
  }

  public boolean servesTraffic(String capability) {
    RouteState state = state(capability);
    return state == RouteState.SHADOW || state == RouteState.NEW || state == RouteState.RETIRED;
  }

  public Map<String, String> asMap() {
    Map<String, String> values = new LinkedHashMap<>();
    states.forEach(
        (capability, state) -> values.put(capability, state.name().toLowerCase(Locale.ROOT)));
    return Collections.unmodifiableMap(values);
  }
}
