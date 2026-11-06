package com.acme.insurance.pas.batch.extract;

import com.acme.insurance.pas.persistence.ExtractRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Record layout of one feed file. Fixed-width layouts are padded with spaces to the LRECL of the
 * legacy DD (200 for POLOUT, 150 for COVOUT); the exposure file is CSV, one line per group, never
 * longer than the EXPOUT LRECL of 300.
 *
 * <p>The field formats are the candidate contract in docs/pas/extracts.md, pending the masked
 * production samples (FU-13) and consumer sign-off (FU-14): text left-aligned and space padded,
 * dates as YYYY-MM-DD, numbers as a sign, zero-padded digits and an explicit decimal point, and
 * spaces for NULL.
 */
public record FeedLayout(String name, Format format, int recordLength, List<Field> fields) {

  public enum Format {
    FIXED_WIDTH,
    CSV
  }

  public enum Kind {
    TEXT,
    DATE,
    DECIMAL,
    INTEGER
  }

  /**
   * One output field. {@code precision}/{@code scale} follow the DB2 column type; {@code width} is
   * the number of characters the field takes in a fixed-width record.
   */
  public record Field(String column, Kind kind, int precision, int scale) {

    public static Field text(String column, int length) {
      return new Field(column, Kind.TEXT, length, 0);
    }

    public static Field date(String column) {
      return new Field(column, Kind.DATE, 10, 0);
    }

    public static Field decimal(String column, int precision, int scale) {
      return new Field(column, Kind.DECIMAL, precision, scale);
    }

    /** SMALLINT / INTEGER / COUNT: sign plus {@code digits} zero-padded digits. */
    public static Field integer(String column, int digits) {
      return new Field(column, Kind.INTEGER, digits, 0);
    }

    public int width() {
      return switch (kind) {
        case TEXT, DATE -> precision;
        case DECIMAL -> precision + (scale > 0 ? 2 : 1);
        case INTEGER -> precision + 1;
      };
    }
  }

  public FeedLayout {
    fields = List.copyOf(fields);
    if (format == Format.FIXED_WIDTH) {
      int used = fields.stream().mapToInt(Field::width).sum();
      if (used > recordLength) {
        throw new IllegalArgumentException(
            name + ": fields take " + used + " bytes, LRECL is " + recordLength);
      }
    }
  }

  public int usedLength() {
    return fields.stream().mapToInt(Field::width).sum();
  }

  /** Optional first line of the file; CSV files carry the column names. */
  public String header() {
    return format == Format.CSV
        ? fields.stream().map(Field::column).collect(Collectors.joining(","))
        : null;
  }

  /** Renders one row as characters, without the record separator. */
  public String render(ExtractRow row) {
    return format == Format.CSV ? renderCsv(row) : renderFixed(row);
  }

  private String renderFixed(ExtractRow row) {
    StringBuilder out = new StringBuilder(recordLength);
    for (Field f : fields) {
      out.append(fixed(f, row.get(f.column())));
    }
    while (out.length() < recordLength) {
      out.append(' ');
    }
    return out.toString();
  }

  private String renderCsv(ExtractRow row) {
    String line =
        fields.stream().map(f -> csv(f, row.get(f.column()))).collect(Collectors.joining(","));
    if (line.length() > recordLength) {
      throw new FeedFormatException(name + ": line is longer than LRECL " + recordLength);
    }
    return line;
  }

  private String fixed(Field f, Object value) {
    if (value == null) {
      return " ".repeat(f.width());
    }
    String text =
        switch (f.kind()) {
          case TEXT -> value.toString();
          case DATE -> ((LocalDate) value).toString();
          case DECIMAL -> signedDigits(f, decimal(f, value));
          case INTEGER -> signedDigits(f, decimal(f, value));
        };
    if (text.length() > f.width()) {
      throw new FeedFormatException(name + "." + f.column() + " does not fit in " + f.width());
    }
    return text + " ".repeat(f.width() - text.length());
  }

  private String csv(Field f, Object value) {
    if (value == null) {
      return "";
    }
    return switch (f.kind()) {
      case TEXT -> quote(value.toString());
      case DATE -> value.toString();
      case DECIMAL, INTEGER -> decimal(f, value).toPlainString();
    };
  }

  private BigDecimal decimal(Field f, Object value) {
    BigDecimal number =
        value instanceof BigDecimal b ? b : new BigDecimal(((Number) value).toString());
    try {
      return number.setScale(f.scale(), RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new FeedFormatException(
          name + "." + f.column() + " has more than " + f.scale() + " decimals");
    }
  }

  private String signedDigits(Field f, BigDecimal number) {
    String digits = number.abs().movePointRight(f.scale()).toBigIntegerExact().toString();
    if (digits.length() > f.precision()) {
      throw new FeedFormatException(
          name + "." + f.column() + " exceeds " + f.precision() + " digits");
    }
    String padded = "0".repeat(f.precision() - digits.length()) + digits;
    String sign = number.signum() < 0 ? "-" : "+";
    if (f.scale() == 0) {
      return sign + padded;
    }
    int point = padded.length() - f.scale();
    return sign + padded.substring(0, point) + "." + padded.substring(point);
  }

  private static String quote(String text) {
    if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0) {
      return text;
    }
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  /** A value that the layout cannot represent; the job fails instead of truncating. */
  public static class FeedFormatException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FeedFormatException(String message) {
      super(message);
    }
  }
}
