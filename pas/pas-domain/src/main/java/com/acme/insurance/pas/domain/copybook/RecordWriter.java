package com.acme.insurance.pas.domain.copybook;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

/** Writes the fields of one record in layout order. */
public final class RecordWriter extends RecordCursor {

  private final byte[] record;
  private final Charset charset;
  private final byte space;

  RecordWriter(CopybookLayout layout, Charset charset) {
    super(layout);
    this.record = new byte[layout.length()];
    this.charset = charset;
    this.space = " ".getBytes(charset)[0];
  }

  /** {@code PIC X(n)}: right-padded with spaces; {@code null} writes spaces. */
  public RecordWriter text(String name, String value) {
    CopybookField.Alphanumeric field = next(name, CopybookField.Alphanumeric.class);
    int offset = offsetOfLast();
    Arrays.fill(record, offset, offset + field.length(), space);
    if (value != null) {
      byte[] bytes = encode(name, value);
      if (bytes.length > field.length()) {
        throw new CopybookDataException(
            layout.name() + "." + name + " is longer than " + field.length() + " bytes");
      }
      System.arraycopy(bytes, 0, record, offset, bytes.length);
    }
    return this;
  }

  /** {@code PIC 9(n)}; {@code null} writes zeros. */
  public RecordWriter number(String name, Integer value) {
    CopybookField.Display field = next(name, CopybookField.Display.class);
    writeDigits(field, value == null ? 0 : value);
    return this;
  }

  /** {@code PIC 9(8)} YYYYMMDD; {@code null} writes zeros. */
  public RecordWriter date(String name, LocalDate value) {
    CopybookField.Display field = next(name, CopybookField.Display.class);
    writeDigits(field, value == null ? 0 : Long.parseLong(value.format(RecordReader.YYYYMMDD)));
    return this;
  }

  /** Writes the date part of a TIMESTAMP into a YYYYMMDD field (Q-521-1). */
  public RecordWriter dateFromTimestamp(String name, LocalDateTime value) {
    return date(name, value == null ? null : value.toLocalDate());
  }

  /** {@code PIC S9(i)V9(s) COMP-3}; {@code null} writes zero. */
  public RecordWriter decimal(String name, BigDecimal value) {
    CopybookField.Packed field = next(name, CopybookField.Packed.class);
    byte[] packed;
    try {
      packed =
          PackedDecimal.encode(
              value == null ? BigDecimal.ZERO : value, field.integerDigits(), field.scale());
    } catch (CopybookDataException e) {
      throw new CopybookDataException(layout.name() + "." + name + ": " + e.getMessage(), e);
    }
    System.arraycopy(packed, 0, record, offsetOfLast(), packed.length);
    return this;
  }

  /** Returns the finished record; fails if a field was not written. */
  public byte[] toBytes() {
    requireComplete();
    return record.clone();
  }

  private void writeDigits(CopybookField.Display field, long value) {
    String text = Long.toString(value);
    if (value < 0 || text.length() > field.digits()) {
      throw new CopybookDataException(
          layout.name() + "." + field.name() + " does not fit PIC 9(" + field.digits() + ")");
    }
    String padded = "0".repeat(field.digits() - text.length()) + text;
    byte[] bytes = padded.getBytes(charset);
    System.arraycopy(bytes, 0, record, offsetOfLast(), bytes.length);
  }

  private byte[] encode(String name, String value) {
    try {
      ByteBuffer buffer =
          charset
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } catch (CharacterCodingException e) {
      throw new CopybookDataException(
          layout.name() + "." + name + " has characters that " + charset + " cannot encode", e);
    }
  }

  @Override
  void onFiller(int offset, int length) {
    Arrays.fill(record, offset, offset + length, space);
  }
}
