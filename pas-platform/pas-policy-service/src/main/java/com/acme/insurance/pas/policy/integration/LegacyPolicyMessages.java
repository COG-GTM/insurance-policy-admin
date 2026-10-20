package com.acme.insurance.pas.policy.integration;

import com.acme.insurance.pas.common.copybook.CopybookField;
import com.acme.insurance.pas.common.copybook.FieldKind;
import com.acme.insurance.pas.common.copybook.PasCopybooks;
import com.acme.insurance.pas.common.copybook.RecordLayout;
import com.acme.insurance.pas.common.copybook.RecordValues;
import com.acme.insurance.pas.common.domain.PolicyRecord;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rebuilds the 125-byte {@code POLICY-RECORD} message a CICS transaction wrote to TDQ/MQ, byte for
 * byte, from the policy row at the event's {@code policyVersion} (AB-528, ADR 0001 D4). Uses the
 * AB-521 {@link PasCopybooks#POLICY_RECORD} layout; production uses {@link
 * PasCopybooks#HOST_CHARSET} (EBCDIC IBM037).
 */
public final class LegacyPolicyMessages {

  private static final RecordLayout LAYOUT = PasCopybooks.POLICY_RECORD;
  private static final int ZONED_3_MODULUS = 1000;
  private static final int MIN_YEAR = 1;
  private static final int MAX_YEAR = 9999;

  private LegacyPolicyMessages() {}

  public static byte[] encode(LegacyMessageSource source, PolicyRecord row, Charset charset) {
    Map<String, Object> all = allValues(row);
    RecordValues populated = new RecordValues();
    for (CopybookField field : LAYOUT.fields()) {
      if (field.kind() != FieldKind.FILLER) {
        String name = field.name();
        populated.put(name, source.populatedFields().contains(name) ? all.get(name) : null);
      }
    }
    Map<String, LocalDate> wrappedDates = new HashMap<>();
    for (CopybookField field : LAYOUT.fields()) {
      if (field.kind() == FieldKind.DATE_YYYYMMDD
          && populated.asMap().get(field.name()) instanceof LocalDate d
          && (d.getYear() < MIN_YEAR || d.getYear() > MAX_YEAR)) {
        wrappedDates.put(field.name(), d);
        populated.put(field.name(), null);
      }
    }
    byte[] out = LAYOUT.encode(populated, charset);
    byte space = " ".getBytes(charset)[0];
    int offset = 0;
    for (CopybookField field : LAYOUT.fields()) {
      String name = field.name();
      if (wrappedDates.containsKey(name)) {
        // Q-07: POLRNW's 99991231 + 10000 wraps PIC 9(8) to 00001231; RecordLayout refuses
        // year 0, so the digits are written directly.
        LocalDate d = wrappedDates.get(name);
        byte[] digits =
            String.format(
                    Locale.ROOT, "%04d%02d%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth())
                .getBytes(charset);
        System.arraycopy(digits, 0, out, offset, field.byteLength());
      } else if (source.unsetFieldsBlank()
          && field.kind() != FieldKind.FILLER
          && !source.populatedFields().contains(name)) {
        Arrays.fill(out, offset, offset + field.byteLength(), space);
      }
      offset += field.byteLength();
    }
    return out;
  }

  /** Host-format (EBCDIC) message. */
  public static byte[] encode(LegacyMessageSource source, PolicyRecord row) {
    return encode(source, row, PasCopybooks.HOST_CHARSET);
  }

  private static Map<String, Object> allValues(PolicyRecord r) {
    // MOVE to an unsigned PIC 9(3) keeps the low-order digits (risk score 1000 -> 000).
    return new RecordValues()
        .put("POLICY-NUMBER", r.policyNumber())
        .put("POLICY-TYPE", r.policyTypeCode())
        .put("POLICY-STATUS", r.policyStatusCode())
        .put("POLICY-EFFECTIVE-DATE", r.effectiveDate())
        .put("POLICY-EXPIRY-DATE", r.expiryDate())
        .put("POLICY-HOLDER-ID", r.holderId())
        .put("POLICY-AGENT-CODE", r.agentCode())
        .put("POLICY-BRANCH-CODE", r.branchCode())
        .put("POLICY-TOTAL-PREMIUM", r.totalPremium())
        .put("POLICY-DEDUCTIBLE", r.deductible())
        .put("POLICY-LIMIT", r.coverageLimit())
        .put("POLICY-INCEPTION-DATE", r.inceptionDate())
        .put("POLICY-RENEWAL-COUNT", zoned3(r.renewalCount()))
        .put("POLICY-UW-STATUS", r.uwStatusCode())
        .put("POLICY-RISK-SCORE", zoned3(r.riskScore()))
        .put("POLICY-WEB-IND", r.webIndicator())
        .put("POLICY-API-FLAG", r.apiFlag())
        .put("POLICY-LAST-UPDATED", r.lastUpdated())
        .put("POLICY-UPDATED-BY", r.updatedBy())
        .asMap();
  }

  private static BigDecimal zoned3(int value) {
    return BigDecimal.valueOf(Math.floorMod(value, ZONED_3_MODULUS));
  }
}
