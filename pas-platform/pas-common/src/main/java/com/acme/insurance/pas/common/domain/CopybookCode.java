package com.acme.insurance.pas.common.domain;

import java.util.Optional;

/**
 * A COBOL 88-level condition mapped to an enum constant. {@link #code()} is the raw value stored in
 * the copybook field and DB2/Postgres column; {@link #conditionName()} is the 88-level name.
 */
public interface CopybookCode {

  String code();

  String conditionName();

  /**
   * Resolves a raw code to its 88-level constant. Legacy data can hold values no 88-level names
   * (for example a policy type PREMBAT rates with its fallback base rate), so callers keep the raw
   * code and treat an empty result as "unrecognised", not as an error.
   */
  static <E extends Enum<E> & CopybookCode> Optional<E> lookup(Class<E> type, String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.stripTrailing();
    for (E constant : type.getEnumConstants()) {
      if (constant.code().equals(trimmed)) {
        return Optional.of(constant);
      }
    }
    return Optional.empty();
  }
}
