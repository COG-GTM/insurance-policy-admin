package com.acme.insurance.pas.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class Scales {
  private Scales() {}

  static BigDecimal normalize(BigDecimal value, int scale) {
    return value == null ? null : value.setScale(scale, RoundingMode.UNNECESSARY);
  }

  static OffsetDateTime utc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
