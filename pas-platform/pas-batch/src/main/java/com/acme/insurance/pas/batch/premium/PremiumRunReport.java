package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.common.domain.PremiumRecord;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * The PREMRPT run report, in PREMBAT's layout: header with run date, one {@code <policy> PREMIUM:
 * <amount>} line per policy, then the three totals. Amounts are printed edited ({@code 904.75})
 * instead of raw packed bytes (quirk Q-24). Detail lines are written per partition into part files
 * (transactional, restart-safe) and concatenated in policy-number order when the run finishes.
 */
public final class PremiumRunReport {

  static final int WIDTH = 132;
  static final String PART_SUFFIX = ".part";

  private PremiumRunReport() {}

  /** Directory holding the part files of one job instance (shared by its restarts). */
  public static Path runDir(Path reportDir, long jobInstanceId) {
    return reportDir.resolve("run-" + jobInstanceId);
  }

  public static Path partFile(Path reportDir, long jobInstanceId, String partitionName) {
    return runDir(reportDir, jobInstanceId).resolve(partitionName + PART_SUFFIX);
  }

  public static Path reportFile(Path reportDir, LocalDate runDate, long jobInstanceId) {
    return reportDir.resolve(
        "PREMRPT-"
            + runDate.format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-"
            + jobInstanceId
            + ".txt");
  }

  public static String detailLine(PremiumRecord r) {
    return r.policyNumber() + " PREMIUM: " + r.totalPremium().toPlainString();
  }

  public static List<String> header(LocalDate runDate) {
    return List.of(
        "ACME INSURANCE - PREMIUM BATCH CALCULATION RUN DATE: "
            + runDate.format(DateTimeFormatter.BASIC_ISO_DATE),
        "-".repeat(WIDTH));
  }

  public static List<String> trailer(PremiumRunTotals t) {
    return List.of(
        "=".repeat(WIDTH),
        String.format("TOTAL POLICIES READ:    %07d", t.read()),
        String.format("TOTAL POLICIES UPDATED: %07d", t.updated()),
        String.format("TOTAL ERRORS:           %07d", t.errors()));
  }

  /** Assembles the report atomically and returns its path. */
  public static Path write(
      Path reportDir, LocalDate runDate, long jobInstanceId, PremiumRunTotals totals) {
    Path target = reportFile(reportDir, runDate, jobInstanceId);
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(reportDir);
      try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        for (String line : header(runDate)) {
          out.write(line);
          out.newLine();
        }
        for (Path part : parts(runDir(reportDir, jobInstanceId))) {
          try (Stream<String> lines = Files.lines(part, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
              out.write(line);
              out.newLine();
            }
          }
        }
        for (String line : trailer(totals)) {
          out.write(line);
          out.newLine();
        }
      }
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write premium run report " + target, e);
    }
  }

  private static List<Path> parts(Path runDir) throws IOException {
    if (!Files.isDirectory(runDir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(runDir)) {
      return files.filter(p -> p.getFileName().toString().endsWith(PART_SUFFIX)).sorted().toList();
    }
  }
}
