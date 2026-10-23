package com.acme.insurance.pas.batch.migration.recon;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CanonicalRowTest {
  @Test
  void canonicalFormNormalizesCharDecimalNullDateAndSourceZoneTimestamp() {
    Map<String, Object> row =
        Map.of(
            "char_value",
            " X  ",
            "amount",
            new BigDecimal("1000.000"),
            "date_value",
            LocalDate.of(2025, 1, 1),
            "timestamp_value",
            LocalDateTime.of(2025, 1, 1, 12, 0));
    var withNull = new java.util.HashMap<>(row);
    withNull.put("nullable", null);

    assertThat(
            CanonicalRow.canonicalForm(
                "TEST",
                List.of("char_value", "amount", "nullable", "date_value", "timestamp_value"),
                withNull,
                ZoneId.of("America/New_York")))
        .isEqualTo(
            "TEST\u001f X\u001f1000\u001f\\N\u001f2025-01-01\u001f2025-01-01T17:00:00.000000Z");
  }

  @Test
  void hashesIgnoreDecimalScaleAndTrailingCharPaddingButHmacUsesSeparateKeyedDigest() {
    var first = Map.<String, Object>of("code", "A  ", "amount", new BigDecimal("10.00"));
    var second = Map.<String, Object>of("code", "A", "amount", new BigDecimal("10.0"));
    var key = new SecretKeySpec(new byte[32], "HmacSHA256");

    assertThat(CanonicalRow.sha256("T", List.of("code", "amount"), first, ZoneId.of("UTC")))
        .isEqualTo(CanonicalRow.sha256("T", List.of("code", "amount"), second, ZoneId.of("UTC")));
    assertThat(
            CanonicalRow.hmacSha256("T", List.of("code", "amount"), first, ZoneId.of("UTC"), key))
        .isEqualTo(
            CanonicalRow.hmacSha256("T", List.of("code", "amount"), second, ZoneId.of("UTC"), key))
        .isNotEqualTo(CanonicalRow.sha256("T", List.of("code", "amount"), first, ZoneId.of("UTC")));
  }
}
