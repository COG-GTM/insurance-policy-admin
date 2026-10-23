package com.acme.insurance.pas.domain;

import java.util.Arrays;
import java.util.Optional;

public record Coded<E extends Enum<E> & CobolCode>(String raw, E value) {
  public Coded {
    raw = raw == null ? "" : raw.trim();
  }

  public static <E extends Enum<E> & CobolCode> Coded<E> of(Class<E> type, String raw) {
    String normalized = raw == null ? "" : raw.trim();
    E value =
        Arrays.stream(type.getEnumConstants())
            .filter(candidate -> candidate.code().equals(normalized))
            .findFirst()
            .orElse(null);
    return new Coded<>(normalized, value);
  }

  public Optional<E> known() {
    return Optional.ofNullable(value);
  }

  public boolean isKnown() {
    return value != null;
  }

  public static <E extends Enum<E> & CobolCode> Coded<E> of(E value) {
    return new Coded<>(value.code(), value);
  }
}
