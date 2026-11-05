package com.acme.insurance.pas.domain.copybook;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** Reads the fields of one record in layout order. */
public final class RecordReader extends RecordCursor {

  static final DateTimeFormatter YYYYMMDD =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

  private final byte[] record;
  private final Charset charset;

  RecordReader(CopybookLayout layout, byte[] record, Charset charset) {
    super(layout);
    this.record = record.clone();
    this.charset = charset;
  }

  /** {@code PIC X(n)}: trailing spaces removed; an all-space field reads as {@code null}. */
  public String text(String name) {
    CopybookField.Alphanumeric field = next(name, CopybookField.Alphanumeric.class);
    String value = new String(record, offsetOfLast(), field.length(), charset).stripTrailing();
    return value.isEmpty() ? null : value;
  }

  /** {@code PIC 9(n)} as an int. */
  public int number(String name) {
    CopybookField.Display field = next(name, CopybookField.Display.class);
    return (int) digits(field);
  }

  /** {@code PIC 9(8)} YYYYMMDD; all zeros reads as {@code null}, an impossible date fails. */
  public LocalDate date(String name) {
    CopybookField.Display field = next(name, CopybookField.Display.class);
    long value = digits(field);
    if (value == 0) {
      return null;
    }
    String text = String.format("%08d", value);
    try {
      return LocalDate.parse(text, YYYYMMDD);
    } catch (DateTimeParseException e) {
      throw new CopybookDataException(
          layout.name() + "." + name + " is not a valid YYYYMMDD date", e);
    }
  }

  /** A YYYYMMDD field mapped to a DB2 TIMESTAMP column: the time is not carried (Q-521-1). */
  public LocalDateTime dateAsTimestamp(String name) {
    LocalDate date = date(name);
    return date == null ? null : date.atStartOfDay();
  }

  /** {@code PIC S9(i)V9(s) COMP-3}. */
  public BigDecimal decimal(String name) {
    CopybookField.Packed field = next(name, CopybookField.Packed.class);
    try {
      return PackedDecimal.decode(
          record,
          offsetOfLast(),
          field.length(),
          field.integerDigits() + field.scale(),
          field.scale());
    } catch (CopybookDataException e) {
      throw new CopybookDataException(layout.name() + "." + name + ": " + e.getMessage(), e);
    }
  }

  /** Call after the last field. */
  public void end() {
    requireComplete();
  }

  private long digits(CopybookField.Display field) {
    String text = new String(record, offsetOfLast(), field.length(), charset);
    long value = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        throw new CopybookDataException(
            layout.name() + "." + field.name() + " is not numeric at position " + (i + 1));
      }
      value = value * 10 + (c - '0');
    }
    return value;
  }

  @Override
  void onFiller(int offset, int length) {}
}
