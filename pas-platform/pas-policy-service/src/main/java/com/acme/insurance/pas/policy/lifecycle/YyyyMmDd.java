package com.acme.insurance.pas.policy.lifecycle;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** COBOL {@code PIC 9(8)} YYYYMMDD dates and {@code FUNCTION INTEGER-OF-DATE}. */
final class YyyyMmDd {

  private static final int YEAR_SHIFT = 10_000;
  private static final int MONTH_SHIFT = 100;

  /** {@code INTEGER-OF-DATE} day 1 is 1601-01-01. */
  private static final LocalDate COBOL_EPOCH = LocalDate.of(1600, 12, 31);

  private static final int FIRST_YEAR = 1601;
  private static final int LAST_YEAR = 9999;

  private YyyyMmDd() {}

  static int of(LocalDate date) {
    return date.getYear() * YEAR_SHIFT + date.getMonthValue() * MONTH_SHIFT + date.getDayOfMonth();
  }

  /** Strict conversion; throws {@link DateTimeException} for an invalid date such as 20250229. */
  static LocalDate toDate(int yyyymmdd) {
    return LocalDate.of(
        yyyymmdd / YEAR_SHIFT, (yyyymmdd / MONTH_SHIFT) % MONTH_SHIFT, yyyymmdd % MONTH_SHIFT);
  }

  /**
   * {@code FUNCTION INTEGER-OF-DATE}: days since 1600-12-31, or 0 for an invalid date (the GnuCOBOL
   * result pinned by golden POLEND-INVDT, quirk register Q-13).
   */
  static long integerOfDate(int yyyymmdd) {
    int year = yyyymmdd / YEAR_SHIFT;
    if (year < FIRST_YEAR || year > LAST_YEAR) {
      return 0;
    }
    try {
      return ChronoUnit.DAYS.between(COBOL_EPOCH, toDate(yyyymmdd));
    } catch (DateTimeException e) {
      return 0;
    }
  }
}
