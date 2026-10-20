package com.acme.insurance.pas.common.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ZonedDecimalTest {

  @Test
  void unsignedRoundTrip() {
    assertThat(ZonedDecimal.encode(new BigDecimal("7"), 3, 0, false)).isEqualTo("007");
    assertThat(ZonedDecimal.encode(new BigDecimal("999"), 3, 0, false)).isEqualTo("999");
    assertThat(ZonedDecimal.decode("000", 3, 0, false)).isEqualTo("0");
  }

  @Test
  void signedUsesTrailingOverpunch() {
    assertThat(ZonedDecimal.encode(new BigDecimal("-12.34"), 4, 2, true)).isEqualTo("123M");
    assertThat(ZonedDecimal.encode(new BigDecimal("12.30"), 4, 2, true)).isEqualTo("123{");
    assertThat(ZonedDecimal.decode("123M", 4, 2, true)).isEqualTo("-12.34");
    assertThat(ZonedDecimal.decode("123}", 4, 2, true)).isEqualTo("-12.30");
    assertThat(ZonedDecimal.decode("1234", 4, 2, true)).isEqualTo("12.34");
  }

  @Test
  void rejectsOverflowAndGarbage() {
    assertThatThrownBy(() -> ZonedDecimal.encode(new BigDecimal("1000"), 3, 0, false))
        .isInstanceOf(CopybookDataException.class);
    assertThatThrownBy(() -> ZonedDecimal.decode("1 3", 3, 0, false))
        .isInstanceOf(CopybookDataException.class);
    assertThatThrownBy(() -> ZonedDecimal.decode("12M", 3, 0, false))
        .isInstanceOf(CopybookDataException.class);
  }
}
