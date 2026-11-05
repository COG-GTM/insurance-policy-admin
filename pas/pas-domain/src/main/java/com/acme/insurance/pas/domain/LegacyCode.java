package com.acme.insurance.pas.domain;

import java.util.Optional;

/**
 * A code value taken from a COBOL 88-level or a DB2 CHAR code column.
 *
 * <p>Domain records keep the raw code as a {@code String} because production data holds values
 * outside the copybook domains (quirks Q-17 and Q-35). Enums give the known values a name; {@link
 * #decode} returns empty for a value the enum does not know instead of failing.
 */
public interface LegacyCode {

  /** The code as stored in DB2 and in the copybook, without CHAR padding. */
  String code();

  /** Decodes a raw code, ignoring trailing CHAR padding. Unknown or null codes give empty. */
  static <E extends Enum<E> & LegacyCode> Optional<E> decode(Class<E> type, String code) {
    if (code == null) {
      return Optional.empty();
    }
    String value = code.stripTrailing();
    for (E constant : type.getEnumConstants()) {
      if (constant.code().equals(value)) {
        return Optional.of(constant);
      }
    }
    return Optional.empty();
  }
}
