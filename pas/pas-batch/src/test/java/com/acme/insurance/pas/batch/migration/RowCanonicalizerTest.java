package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RowCanonicalizerTest {

  @Test
  void db2AndPostgresqlRepresentationsHashTheSame() {
    Object[] db2 = {
      "POL00000001 ",
      (short) 1,
      new BigDecimal("12.50"),
      Date.valueOf("2026-01-01"),
      Timestamp.valueOf("2026-01-01 08:00:00.123456"),
      null
    };
    Object[] pg = {
      "POL00000001",
      1,
      new BigDecimal("12.50"),
      Date.valueOf("2026-01-01"),
      Timestamp.valueOf("2026-01-01 08:00:00.123456"),
      null
    };
    assertThat(RowCanonicalizer.hash(db2)).isEqualTo(RowCanonicalizer.hash(pg)).hasSize(64);
  }

  @Test
  void anyValueChangeChangesTheHash() {
    Object[] row = {"A", new BigDecimal("1.00"), null};
    String base = RowCanonicalizer.hash(row);
    Object[] changed = Arrays.copyOf(row, row.length);
    changed[1] = new BigDecimal("1.01");
    assertThat(RowCanonicalizer.hash(changed)).isNotEqualTo(base);
    Object[] emptyInsteadOfNull = {"A", new BigDecimal("1.00"), ""};
    assertThat(RowCanonicalizer.hash(emptyInsteadOfNull)).isNotEqualTo(base);
    Object[] shifted = {"A\u001f", new BigDecimal("1.00")};
    assertThat(RowCanonicalizer.hash(shifted)).isNotEqualTo(base);
  }

  @Test
  void keysAreTrimmedAndJoined() {
    assertThat(
            RowCanonicalizer.key(
                java.util.List.of("POL1  ", (short) 2, Date.valueOf("2026-03-01"))))
        .isEqualTo("POL1|2|2026-03-01");
  }
}
