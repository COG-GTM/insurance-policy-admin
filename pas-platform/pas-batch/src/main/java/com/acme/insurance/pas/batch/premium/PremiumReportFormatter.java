package com.acme.insurance.pas.batch.premium;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PremiumReportFormatter {
  private static final int RECORD_LENGTH = 132;
  private static final DateTimeFormatter RUN_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private PremiumReportFormatter() {}

  public static List<String> format(
      LocalDate businessDate, List<PremiumReportItem> items, long read, long updated, long errors) {
    List<String> lines = new ArrayList<>();
    lines.add(
        record(
            "ACME INSURANCE - PREMIUM BATCH CALCULATION RUN DATE: "
                + RUN_DATE.format(businessDate)));
    lines.add("-".repeat(RECORD_LENGTH));
    for (PremiumReportItem item : items) {
      String line =
          item.status().equals("ERROR")
              ? item.policyNumber() + " ERROR: " + item.errorType()
              : item.policyNumber() + " PREMIUM: " + formatPremium(item.totalPremium());
      lines.add(record(line));
    }
    lines.add("=".repeat(RECORD_LENGTH));
    lines.add(record("TOTAL POLICIES READ:    " + count(read)));
    lines.add(record("TOTAL POLICIES UPDATED: " + count(updated)));
    lines.add(record("TOTAL ERRORS:           " + count(errors)));
    return List.copyOf(lines);
  }

  public static String formatPremium(BigDecimal amount) {
    BigDecimal value = amount.setScale(2, RoundingMode.UNNECESSARY);
    String[] parts = value.toPlainString().split("\\.", -1);
    return String.format(
        Locale.ROOT, "%09d.%02d", Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
  }

  private static String count(long value) {
    return String.format(Locale.ROOT, "%07d", value);
  }

  private static String record(String value) {
    if (value.length() >= RECORD_LENGTH) {
      return value.substring(0, RECORD_LENGTH);
    }
    return value + " ".repeat(RECORD_LENGTH - value.length());
  }

  public record PremiumReportItem(
      String policyNumber, String status, BigDecimal totalPremium, String errorType) {}
}
