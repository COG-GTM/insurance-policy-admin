package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.persistence.PremiumRunItem;
import com.acme.insurance.pas.persistence.PremiumRunTotals;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * The run report that replaces DD PREMRPT ({@code ACME.PAS.REPORTS.PREMIUM.D&LYYMMDD}, RECFM=F,
 * LRECL=132): a header, one detail line per policy read in policy-number order, and the READ /
 * UPDATED / ERRORS totals, each a 132-byte record followed by a line feed.
 *
 * <p>PREMBAT STRINGs the packed {@code PIC S9(9)V99 COMP-3} premium into its detail line, so the
 * line carries six raw packed-decimal bytes rather than a printable amount (Q-29, decision
 * preserve). This report keeps those bytes (ISO-8859-1, one byte per character) so it matches the
 * COBOL report on identical input; whether production consumers need it unchanged waits on the
 * production sample of FU-13.
 */
public final class PremiumReport {

  public static final int RECORD_LENGTH = 132;
  static final int PACKED_DIGITS = 11;
  static final int PACKED_SCALE = 2;

  private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  private PremiumReport() {}

  /** File name after the GDG-less legacy DSN, one per business date; a rerun replaces it. */
  public static String fileName(LocalDate businessDate) {
    return "ACME.PAS.REPORTS.PREMIUM.D" + businessDate.format(YYMMDD) + ".txt";
  }

  /**
   * Writes the report atomically (temporary file, then rename) and returns its path.
   *
   * @param details calls its argument once per outcome, in policy-number order
   */
  public static Path write(
      Path dir,
      LocalDate businessDate,
      Consumer<Consumer<PremiumRunItem>> details,
      PremiumRunTotals totals) {
    try {
      Files.createDirectories(dir);
      Path target = dir.resolve(fileName(businessDate));
      Path temp = Files.createTempFile(dir, ".premium-report-", ".part");
      boolean moved = false;
      try {
        try (OutputStream out = Files.newOutputStream(temp)) {
          render(businessDate, details, totals, out);
        }
        Path written =
            Files.move(
                temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        moved = true;
        return written;
      } finally {
        if (!moved) {
          deleteQuietly(temp);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteQuietly(Path temp) {
    try {
      Files.deleteIfExists(temp);
    } catch (IOException ignored) {
      // the original failure is the one to report
    }
  }

  static void render(
      LocalDate businessDate,
      Consumer<Consumer<PremiumRunItem>> details,
      PremiumRunTotals totals,
      OutputStream out)
      throws IOException {
    record(
        out,
        text(
            "ACME INSURANCE - PREMIUM BATCH CALCULATION RUN DATE: "
                + businessDate.format(YYYYMMDD)));
    record(out, repeat('-'));
    try {
      details.accept(
          item -> {
            try {
              record(out, detail(item));
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
    record(out, repeat('='));
    record(out, text("TOTAL POLICIES READ:    " + count(totals.read())));
    record(out, text("TOTAL POLICIES UPDATED: " + count(totals.updated())));
    record(out, text("TOTAL ERRORS:           " + count(totals.errors())));
  }

  /** {@code <policy number> PREMIUM: <6 packed bytes>}; a policy that was not rated packs zero. */
  static byte[] detail(PremiumRunItem item) {
    byte[] prefix = text(item.policyNumber() + " PREMIUM: ");
    byte[] packed = packed(item.totalPremium() == null ? BigDecimal.ZERO : item.totalPremium());
    byte[] line = new byte[prefix.length + packed.length];
    System.arraycopy(prefix, 0, line, 0, prefix.length);
    System.arraycopy(packed, 0, line, prefix.length, packed.length);
    return line;
  }

  /**
   * {@code PIC S9(9)V99 COMP-3}: 11 digits and a sign nibble (C positive, D negative) in 6 bytes,
   * high-order digits truncated as a COBOL MOVE would.
   */
  static byte[] packed(BigDecimal amount) {
    BigDecimal scaled = amount.setScale(PACKED_SCALE, RoundingMode.DOWN);
    String digits = scaled.unscaledValue().abs().toString();
    if (digits.length() > PACKED_DIGITS) {
      digits = digits.substring(digits.length() - PACKED_DIGITS);
    }
    digits = "0".repeat(PACKED_DIGITS - digits.length()) + digits;
    int[] nibbles = new int[PACKED_DIGITS + 1];
    for (int i = 0; i < PACKED_DIGITS; i++) {
      nibbles[i] = digits.charAt(i) - '0';
    }
    nibbles[PACKED_DIGITS] = scaled.signum() < 0 ? 0xD : 0xC;
    byte[] bytes = new byte[nibbles.length / 2];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) ((nibbles[2 * i] << 4) | nibbles[2 * i + 1]);
    }
    return bytes;
  }

  private static String count(long value) {
    return String.format("%07d", value % 10_000_000L);
  }

  private static byte[] text(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] repeat(char c) {
    byte[] line = new byte[RECORD_LENGTH];
    Arrays.fill(line, (byte) c);
    return line;
  }

  private static void record(OutputStream out, byte[] content) throws IOException {
    byte[] record = new byte[RECORD_LENGTH];
    Arrays.fill(record, (byte) ' ');
    System.arraycopy(content, 0, record, 0, Math.min(content.length, RECORD_LENGTH));
    out.write(record);
    out.write('\n');
  }
}
