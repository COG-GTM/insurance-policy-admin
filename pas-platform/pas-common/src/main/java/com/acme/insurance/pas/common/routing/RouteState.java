package com.acme.insurance.pas.common.routing;

import java.util.Locale;

public enum RouteState {
  LEGACY,
  SHADOW,
  NEW,
  RETIRED;

  public static RouteState parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Route state cannot be null");
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "legacy" -> LEGACY;
      case "shadow" -> SHADOW;
      case "new", "modern" -> NEW;
      case "retired" -> RETIRED;
      default -> throw new IllegalArgumentException("Unknown route state: " + value);
    };
  }
}
