package com.acme.insurance.pas.copybook;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;

/**
 * Fixed-width codec for COBOL DISPLAY and COMP-3 values. Numeric writes reject overflow by default;
 * TRUNCATE models COBOL MOVE by dropping high integer digits and excess fractional digits.
 */
public final class CopybookCodec {
  private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";
  private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

  private final RecordLayout layout;
  private final CopybookCharset charset;

  public CopybookCodec(RecordLayout layout, CopybookCharset charset) {
    this.layout = layout;
    this.charset = charset;
  }

  public RecordReader reader(byte[] record) {
    if (record == null || record.length != layout.length()) {
      throw new CopybookDataException(layout.name(), 0, "record length mismatch");
    }
    return new RecordReader(record.clone());
  }

  public RecordWriter writer() {
    return new RecordWriter();
  }

  public final class RecordReader {
    private final byte[] record;

    private RecordReader(byte[] record) {
      this.record = record;
    }

    public String string(String name) {
      FieldDescriptor field = layout.field(name);
      if (!(field.type() instanceof FieldType.Alphanumeric)) {
        throw typeError(field, "not an alphanumeric field");
      }
      String value = decodeText(field);
      return trimRight(value);
    }

    public BigDecimal decimal(String name) {
      FieldDescriptor field = layout.field(name);
      if (field.type() instanceof FieldType.PackedDecimal type) {
        return decodePacked(field, type);
      }
      if (field.type() instanceof FieldType.SignedDisplay type) {
        return decodeDisplay(field, type.digits(), type.scale(), true);
      }
      if (field.type() instanceof FieldType.UnsignedDisplay type) {
        return decodeDisplay(field, type.digits(), type.scale(), false);
      }
      throw typeError(field, "not a numeric field");
    }

    public int integer(String name) {
      try {
        return decimal(name).intValueExact();
      } catch (ArithmeticException ex) {
        FieldDescriptor field = layout.field(name);
        throw typeError(field, "integer is out of range");
      }
    }

    public LocalDate date(String name) {
      FieldDescriptor field = layout.field(name);
      if (!(field.type() instanceof FieldType.DateYyyyMmDd)) {
        throw typeError(field, "not a date field");
      }
      String raw = decodeText(field);
      if (raw.equals("        ") || raw.equals("00000000")) {
        return null;
      }
      if (raw.equals("99999999")) {
        return CopybookDates.HIGH_DATE;
      }
      try {
        LocalDate date = LocalDate.parse(raw, DATE_FORMAT);
        if (date.getYear() < 1 || date.getYear() > 9999) {
          throw new DateTimeException("year outside copybook range");
        }
        return date;
      } catch (DateTimeException ex) {
        throw typeError(field, "invalid YYYYMMDD date");
      }
    }

    private String decodeText(FieldDescriptor field) {
      try {
        return charset
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(record, field.offset(), field.type().byteLength()))
            .toString();
      } catch (CharacterCodingException ex) {
        throw typeError(field, "invalid character encoding");
      }
    }

    private BigDecimal decodeDisplay(FieldDescriptor field, int digits, int scale, boolean signed) {
      int offset = field.offset();
      StringBuilder number = new StringBuilder(digits);
      boolean negative = false;
      for (int index = 0; index < digits; index++) {
        int value = record[offset + index] & 0xff;
        int digit;
        if (signed && index == digits - 1) {
          if (charset == CopybookCharset.EBCDIC) {
            int zone = (value >>> 4) & 0xf;
            digit = value & 0xf;
            if (digit > 9) {
              throw typeError(field, "invalid signed display digit");
            } else if (zone == 0xd) {
              negative = true;
            } else if (zone != 0xc && zone != 0xf) {
              throw typeError(field, "invalid signed display digit");
            }
          } else {
            char character = (char) value;
            int positive = POSITIVE_OVERPUNCH.indexOf(character);
            int negativeIndex = NEGATIVE_OVERPUNCH.indexOf(character);
            if (positive >= 0) {
              digit = positive;
            } else if (negativeIndex >= 0) {
              digit = negativeIndex;
              negative = true;
            } else if (character >= '0' && character <= '9') {
              digit = character - '0';
            } else {
              throw typeError(field, "invalid signed display digit");
            }
          }
        } else {
          digit = decodeDigit(value, field);
        }
        number.append((char) ('0' + digit));
      }
      BigDecimal result = decimalFromDigits(field, number.toString(), scale);
      return negative ? result.negate() : result;
    }

    private BigDecimal decimalFromDigits(FieldDescriptor field, String digits, int scale) {
      try {
        return new BigDecimal(new BigInteger(digits), scale);
      } catch (NumberFormatException ex) {
        throw typeError(field, "invalid numeric value");
      }
    }

    private int decodeDigit(int value, FieldDescriptor field) {
      if (charset == CopybookCharset.EBCDIC) {
        if ((value >>> 4) != 0xf || (value & 0xf) > 9) {
          throw typeError(field, "invalid display digit");
        }
        return value & 0xf;
      }
      if (value < '0' || value > '9') {
        throw typeError(field, "invalid display digit");
      }
      return value - '0';
    }

    private BigDecimal decodePacked(FieldDescriptor field, FieldType.PackedDecimal type) {
      byte[] bytes = Arrays.copyOfRange(record, field.offset(), field.offset() + type.byteLength());
      int digitStart = 0;
      StringBuilder digits = new StringBuilder(type.digits());
      if (type.digits() % 2 == 0) {
        if (((bytes[0] >>> 4) & 0xf) != 0) {
          throw typeError(field, "invalid packed-decimal pad nibble");
        }
        digitStart = 1;
      }
      for (int index = digitStart; digits.length() < type.digits(); index++) {
        int high = (bytes[index / 2] >>> 4) & 0xf;
        int low = bytes[index / 2] & 0xf;
        int nibble = (index % 2 == 0) ? high : low;
        if (nibble > 9) {
          throw typeError(field, "invalid packed-decimal digit");
        }
        digits.append((char) ('0' + nibble));
      }
      int last = bytes[bytes.length - 1] & 0xf;
      boolean negative =
          switch (last) {
            case 0xd, 0xb -> true;
            case 0xc, 0xa, 0xe, 0xf -> false;
            default -> throw typeError(field, "invalid packed-decimal sign");
          };
      BigDecimal result = decimalFromDigits(field, digits.toString(), type.scale());
      return negative ? result.negate() : result;
    }
  }

  public final class RecordWriter {
    private final byte[] record = new byte[layout.length()];

    private RecordWriter() {
      Arrays.fill(record, encodeChar(' '));
      for (FieldDescriptor field : layout.fields()) {
        if (field.type() instanceof FieldType.Filler filler) {
          Arrays.fill(record, field.offset(), field.offset() + filler.length(), encodeChar(' '));
        } else if (field.type() instanceof FieldType.PackedDecimal
            || field.type() instanceof FieldType.SignedDisplay
            || field.type() instanceof FieldType.UnsignedDisplay) {
          putDecimal(field.name(), BigDecimal.ZERO);
        }
      }
    }

    public RecordWriter putString(String name, String value) {
      return putString(name, value, OverflowPolicy.REJECT);
    }

    public RecordWriter putString(String name, String value, OverflowPolicy policy) {
      FieldDescriptor field = layout.field(name);
      if (!(field.type() instanceof FieldType.Alphanumeric type)) {
        throw typeError(field, "not an alphanumeric field");
      }
      String actual = value == null ? "" : value;
      int length = type.length();
      if (actual.length() > length) {
        if (policy == OverflowPolicy.REJECT) {
          throw typeError(field, "text exceeds field width");
        }
        actual = actual.substring(0, length);
      }
      String padded = actual + " ".repeat(length - actual.length());
      try {
        ByteBuffer bytes =
            charset
                .charset()
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(padded));
        bytes.get(record, field.offset(), length);
      } catch (CharacterCodingException ex) {
        throw typeError(field, "character cannot be represented");
      }
      return this;
    }

    public RecordWriter putDecimal(String name, BigDecimal value) {
      return putDecimal(name, value, OverflowPolicy.REJECT);
    }

    public RecordWriter putDecimal(String name, BigDecimal value, OverflowPolicy policy) {
      FieldDescriptor field = layout.field(name);
      int digits;
      int scale;
      boolean signed;
      if (field.type() instanceof FieldType.PackedDecimal type) {
        digits = type.digits();
        scale = type.scale();
        signed = type.signed();
      } else if (field.type() instanceof FieldType.SignedDisplay type) {
        digits = type.digits();
        scale = type.scale();
        signed = true;
      } else if (field.type() instanceof FieldType.UnsignedDisplay type) {
        digits = type.digits();
        scale = type.scale();
        signed = false;
      } else {
        throw typeError(field, "not a numeric field");
      }
      BigDecimal number = value == null ? BigDecimal.ZERO : value;
      if (!signed && number.signum() < 0 && policy == OverflowPolicy.REJECT) {
        throw typeError(field, "negative value in unsigned field");
      }
      if (!signed) {
        number = number.abs();
      }
      try {
        number =
            number.setScale(
                scale,
                policy == OverflowPolicy.TRUNCATE ? RoundingMode.DOWN : RoundingMode.UNNECESSARY);
      } catch (ArithmeticException ex) {
        throw typeError(field, "fractional scale exceeds field");
      }
      BigInteger unscaled = number.unscaledValue().abs();
      BigInteger limit = BigInteger.TEN.pow(digits);
      if (unscaled.compareTo(limit) >= 0) {
        if (policy == OverflowPolicy.REJECT) {
          throw typeError(field, "numeric value exceeds field capacity");
        }
        BigInteger modulus = BigInteger.TEN.pow(digits);
        unscaled = unscaled.remainder(modulus);
      }
      String unscaledDigits = unscaled.toString();
      String magnitude = "0".repeat(digits - unscaledDigits.length()) + unscaledDigits;
      boolean negative = signed && value != null && value.signum() < 0 && unscaled.signum() != 0;
      if (field.type() instanceof FieldType.PackedDecimal packed) {
        encodePacked(field, magnitude, negative, packed.signed());
      } else {
        encodeDisplay(field, magnitude, negative, signed);
      }
      return this;
    }

    public RecordWriter putInteger(String name, Integer value) {
      return putDecimal(name, value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value));
    }

    public RecordWriter putDate(String name, LocalDate value) {
      FieldDescriptor field = layout.field(name);
      if (!(field.type() instanceof FieldType.DateYyyyMmDd)) {
        throw typeError(field, "not a date field");
      }
      String text;
      if (value == null) {
        text = "00000000";
      } else if (value.equals(CopybookDates.HIGH_DATE)) {
        text = "99999999";
      } else if (value.getYear() < 1 || value.getYear() > 9999) {
        throw typeError(field, "date year outside copybook range");
      } else {
        text = DATE_FORMAT.format(value);
      }
      encodeText(field, text);
      return this;
    }

    public byte[] toByteArray() {
      return record.clone();
    }

    private void encodeDisplay(
        FieldDescriptor field, String magnitude, boolean negative, boolean signed) {
      for (int index = 0; index < magnitude.length(); index++) {
        int digit = magnitude.charAt(index) - '0';
        int value;
        if (signed && index == magnitude.length() - 1) {
          if (charset == CopybookCharset.EBCDIC) {
            value = ((negative ? 0xd : 0xc) << 4) | digit;
          } else {
            char overpunch = (negative ? NEGATIVE_OVERPUNCH : POSITIVE_OVERPUNCH).charAt(digit);
            value = overpunch;
          }
        } else {
          value = encodeDigit(digit);
        }
        record[field.offset() + index] = (byte) value;
      }
    }

    private void encodePacked(
        FieldDescriptor field, String magnitude, boolean negative, boolean signed) {
      int totalNibbles = field.type().byteLength() * 2;
      int pad = totalNibbles - magnitude.length() - 1;
      int[] nibbles = new int[totalNibbles];
      int source = 0;
      for (int index = pad; index < totalNibbles - 1; index++) {
        nibbles[index] = magnitude.charAt(source++) - '0';
      }
      nibbles[totalNibbles - 1] = signed ? (negative ? 0xd : 0xc) : 0xf;
      for (int index = 0; index < field.type().byteLength(); index++) {
        record[field.offset() + index] =
            (byte) ((nibbles[index * 2] << 4) | nibbles[index * 2 + 1]);
      }
    }

    private void encodeText(FieldDescriptor field, String text) {
      try {
        ByteBuffer bytes =
            charset
                .charset()
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text));
        bytes.get(record, field.offset(), field.type().byteLength());
      } catch (CharacterCodingException ex) {
        throw typeError(field, "character cannot be represented");
      }
    }

    private int encodeDigit(int digit) {
      return charset == CopybookCharset.EBCDIC ? 0xf0 | digit : '0' + digit;
    }

    private byte encodeChar(char character) {
      try {
        ByteBuffer bytes =
            charset.charset().newEncoder().encode(java.nio.CharBuffer.wrap(new char[] {character}));
        return bytes.get();
      } catch (CharacterCodingException ex) {
        throw new IllegalArgumentException("Space cannot be represented", ex);
      }
    }
  }

  private CopybookDataException typeError(FieldDescriptor field, String reason) {
    return new CopybookDataException(field.name(), field.offset(), reason);
  }

  private static String trimRight(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(0, end);
  }
}
