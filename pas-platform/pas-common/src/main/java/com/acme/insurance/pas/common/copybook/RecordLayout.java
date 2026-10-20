package com.acme.insurance.pas.common.copybook;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-width record layout (a flattened copybook) with encode/decode to bytes in a single-byte
 * charset (EBCDIC {@code IBM037} for host records, or an ASCII charset for distributed files).
 */
public final class RecordLayout {

  private static final DateTimeFormatter YYYYMMDD =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

  private final String name;
  private final List<CopybookField> fields;
  private final Map<String, Integer> offsets;
  private final int length;

  public RecordLayout(String name, List<CopybookField> fields) {
    this.name = Objects.requireNonNull(name, "name");
    this.fields = List.copyOf(fields);
    Map<String, Integer> map = new LinkedHashMap<>();
    int offset = 0;
    for (CopybookField field : this.fields) {
      if (field.kind() != FieldKind.FILLER && map.put(field.name(), offset) != null) {
        throw new IllegalArgumentException("duplicate field " + field.name() + " in " + name);
      }
      offset += field.byteLength();
    }
    this.offsets = Collections.unmodifiableMap(map);
    this.length = offset;
  }

  public String name() {
    return name;
  }

  public List<CopybookField> fields() {
    return fields;
  }

  /** Total record length in bytes (LRECL). */
  public int length() {
    return length;
  }

  /** Byte offset of a named field. */
  public int offsetOf(String fieldName) {
    Integer offset = offsets.get(fieldName);
    if (offset == null) {
      throw new IllegalArgumentException("unknown field " + fieldName + " in " + name);
    }
    return offset;
  }

  public RecordValues decode(byte[] record, Charset charset) {
    Objects.requireNonNull(record, "record");
    if (record.length != length) {
      throw new CopybookDataException(
          name + ": expected " + length + " bytes, got " + record.length);
    }
    CharsetDecoder decoder = singleByte(charset).newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPORT);
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    RecordValues values = new RecordValues();
    int offset = 0;
    for (CopybookField field : fields) {
      try {
        switch (field.kind()) {
          case FILLER -> {}
          case PACKED ->
              values.put(
                  field.name(),
                  PackedDecimal.decode(
                      record, offset, field.digits(), field.scale(), field.signed()));
          case ALPHANUMERIC ->
              values.put(field.name(), rtrim(text(decoder, record, offset, field.digits())));
          case ZONED ->
              values.put(
                  field.name(),
                  ZonedDecimal.decode(
                      text(decoder, record, offset, field.digits()),
                      field.digits(),
                      field.scale(),
                      field.signed()));
          case DATE_YYYYMMDD ->
              values.put(field.name(), parseDate(text(decoder, record, offset, field.digits())));
        }
      } catch (CopybookDataException e) {
        throw new CopybookDataException(
            name + "." + field.name() + " @" + offset + ": " + e.getMessage(), e);
      }
      offset += field.byteLength();
    }
    return values;
  }

  public byte[] encode(RecordValues values, Charset charset) {
    Objects.requireNonNull(values, "values");
    CharsetEncoder encoder = singleByte(charset).newEncoder();
    encoder.onMalformedInput(CodingErrorAction.REPORT);
    encoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    byte[] out = new byte[length];
    int offset = 0;
    for (CopybookField field : fields) {
      try {
        switch (field.kind()) {
          case FILLER -> put(encoder, out, offset, " ".repeat(field.digits()));
          case PACKED -> {
            BigDecimal value = (BigDecimal) values.get(field.name());
            PackedDecimal.encode(
                value == null ? BigDecimal.ZERO : value,
                out,
                offset,
                field.digits(),
                field.scale(),
                field.signed());
          }
          case ALPHANUMERIC -> {
            String value = (String) values.get(field.name());
            String text = value == null ? "" : value;
            if (text.length() > field.digits()) {
              throw new CopybookDataException(
                  "text of length " + text.length() + " exceeds PIC X(" + field.digits() + ")");
            }
            put(encoder, out, offset, text + " ".repeat(field.digits() - text.length()));
          }
          case ZONED -> {
            BigDecimal value = (BigDecimal) values.get(field.name());
            put(
                encoder,
                out,
                offset,
                ZonedDecimal.encode(
                    value == null ? BigDecimal.ZERO : value,
                    field.digits(),
                    field.scale(),
                    field.signed()));
          }
          case DATE_YYYYMMDD -> {
            LocalDate value = (LocalDate) values.get(field.name());
            if (value != null && (value.getYear() < 1 || value.getYear() > 9999)) {
              throw new CopybookDataException("year out of range for YYYYMMDD: " + value);
            }
            put(encoder, out, offset, value == null ? "00000000" : value.format(YYYYMMDD));
          }
        }
      } catch (ClassCastException e) {
        throw new CopybookDataException(
            name + "." + field.name() + ": wrong value type for " + field.kind(), e);
      } catch (CopybookDataException e) {
        throw new CopybookDataException(name + "." + field.name() + ": " + e.getMessage(), e);
      }
      offset += field.byteLength();
    }
    return out;
  }

  private static Charset singleByte(Charset charset) {
    Objects.requireNonNull(charset, "charset");
    if (charset.newEncoder().maxBytesPerChar() != 1.0f) {
      throw new IllegalArgumentException("fixed-width records need a single-byte charset");
    }
    return charset;
  }

  private static String text(CharsetDecoder decoder, byte[] record, int offset, int len) {
    try {
      return decoder.reset().decode(ByteBuffer.wrap(record, offset, len)).toString();
    } catch (CharacterCodingException e) {
      throw new CopybookDataException("undecodable bytes in text field", e);
    }
  }

  private static void put(CharsetEncoder encoder, byte[] out, int offset, String text) {
    try {
      ByteBuffer bytes = encoder.reset().encode(CharBuffer.wrap(text));
      if (bytes.remaining() != text.length()) {
        throw new CopybookDataException("charset did not encode one byte per character");
      }
      bytes.get(out, offset, text.length());
    } catch (CharacterCodingException e) {
      throw new CopybookDataException("character not representable in record charset", e);
    }
  }

  private static LocalDate parseDate(String text) {
    if (text.chars().allMatch(c -> c == '0') || text.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(text, YYYYMMDD);
    } catch (DateTimeParseException e) {
      throw new CopybookDataException("invalid YYYYMMDD date '" + text + "'", e);
    }
  }

  private static String rtrim(String text) {
    int end = text.length();
    while (end > 0 && text.charAt(end - 1) == ' ') {
      end--;
    }
    return text.substring(0, end);
  }

  @Override
  public String toString() {
    return name + Arrays.toString(new int[] {length});
  }

  /** Builder that keeps field declarations readable next to the copybook source. */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static final class Builder {
    private final String name;
    private final List<CopybookField> fields = new ArrayList<>();

    private Builder(String name) {
      this.name = name;
    }

    public Builder field(CopybookField field) {
      fields.add(field);
      return this;
    }

    public RecordLayout build() {
      return new RecordLayout(name, fields);
    }
  }
}
