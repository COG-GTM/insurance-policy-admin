package com.acme.insurance.pas.batch.migration.parallelrun;

import com.acme.insurance.pas.batch.extract.ExtractOutputs;
import com.acme.insurance.pas.common.copybook.CopybookField;
import com.acme.insurance.pas.common.copybook.RecordLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Compares a CRLF-terminated extract file record by record. Legacy files are read from {@code
 * <legacyDir>/<job>/<business-date>/<file>} (same layout as {@link ExtractOutputs}), new files from
 * the AB-527 job output location. Fixed-width files are keyed and diffed with their {@link
 * RecordLayout}; CSV files by leading columns and header names.
 */
public final class LineFileAdapter implements OutputAdapter {

  private final String jobName;
  private final String fileName;
  private final Path legacyDir;
  private final ExtractOutputs outputs;
  private final Predicate<LocalDate> schedule;
  private final Function<String, String> keyOf;
  private final FieldDiff fieldDiff;

  @FunctionalInterface
  interface FieldDiff {
    List<String> diff(String legacy, String candidate);
  }

  private LineFileAdapter(
      String jobName,
      String fileName,
      Path legacyDir,
      ExtractOutputs outputs,
      Predicate<LocalDate> schedule,
      Function<String, String> keyOf,
      FieldDiff fieldDiff) {
    this.jobName = jobName;
    this.fileName = fileName;
    this.legacyDir = legacyDir;
    this.outputs = outputs;
    this.schedule = schedule;
    this.keyOf = keyOf;
    this.fieldDiff = fieldDiff;
  }

  /** Fixed-width extract keyed by {@code keyFields} of {@code layout}. */
  public static LineFileAdapter fixedWidth(
      String jobName,
      String fileName,
      Path legacyDir,
      ExtractOutputs outputs,
      Predicate<LocalDate> schedule,
      RecordLayout layout,
      String... keyFields) {
    Function<String, String> key =
        line -> {
          StringBuilder sb = new StringBuilder();
          for (String field : keyFields) {
            if (!sb.isEmpty()) {
              sb.append('|');
            }
            sb.append(slice(line, layout, field).strip());
          }
          return sb.toString();
        };
    FieldDiff diff =
        (a, b) -> {
          List<String> changed = new ArrayList<>();
          int offset = 0;
          for (CopybookField f : layout.fields()) {
            int end = offset + f.byteLength();
            if (!slice(a, offset, end).equals(slice(b, offset, end))) {
              changed.add("FILLER".equals(f.name()) ? "FILLER@" + offset : f.name());
            }
            offset = end;
          }
          if (!slice(a, offset, Integer.MAX_VALUE).equals(slice(b, offset, Integer.MAX_VALUE))) {
            changed.add("BEYOND_LRECL@" + offset);
          }
          return changed;
        };
    return new LineFileAdapter(jobName, fileName, legacyDir, outputs, schedule, key, diff);
  }

  /** CSV with a header record, keyed by the first {@code keyColumns} columns. */
  public static LineFileAdapter csv(
      String jobName,
      String fileName,
      Path legacyDir,
      ExtractOutputs outputs,
      Predicate<LocalDate> schedule,
      List<String> header,
      int keyColumns) {
    Function<String, String> key =
        line -> {
          List<String> cols = columns(line);
          if (cols.equals(header)) {
            return "#HEADER";
          }
          return String.join("|", cols.subList(0, Math.min(keyColumns, cols.size())));
        };
    FieldDiff diff =
        (a, b) -> {
          List<String> x = columns(a);
          List<String> y = columns(b);
          List<String> changed = new ArrayList<>();
          for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            String left = i < x.size() ? x.get(i) : null;
            String right = i < y.size() ? y.get(i) : null;
            if (left == null || !left.equals(right)) {
              changed.add(i < header.size() ? header.get(i) : "COL" + (i + 1));
            }
          }
          return changed;
        };
    return new LineFileAdapter(jobName, fileName, legacyDir, outputs, schedule, key, diff);
  }

  @Override
  public String scope() {
    return jobName + "/" + fileName;
  }

  @Override
  public boolean runsOn(LocalDate businessDate) {
    return schedule.test(businessDate);
  }

  @Override
  public Optional<Records> legacy(LocalDate businessDate) throws IOException {
    return read(legacyDir.resolve(jobName).resolve(businessDate.toString()).resolve(fileName));
  }

  @Override
  public Optional<Records> candidate(LocalDate businessDate) throws IOException {
    return read(outputs.location(jobName, businessDate, fileName));
  }

  @Override
  public List<String> changedFields(String legacy, String candidate) {
    return fieldDiff.diff(legacy, candidate);
  }

  private Optional<Records> read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    String content = Files.readString(file, ExtractOutputs.RECORD_CHARSET);
    Map<String, String> byKey = new LinkedHashMap<>();
    for (String line : content.split("\r?\n")) {
      if (line.isEmpty()) {
        continue;
      }
      String key = keyOf.apply(line);
      String unique = key;
      for (int n = 2; byKey.containsKey(unique); n++) {
        unique = key + "#" + n;
      }
      byKey.put(unique, line);
    }
    return Optional.of(new Records(file.toString(), byKey));
  }

  private static String slice(String line, RecordLayout layout, String field) {
    int start = layout.offsetOf(field);
    int length =
        layout.fields().stream()
            .filter(f -> f.name().equals(field))
            .findFirst()
            .orElseThrow()
            .byteLength();
    return slice(line, start, start + length);
  }

  private static String slice(String line, int start, int end) {
    if (start >= line.length()) {
      return "";
    }
    return line.substring(start, Math.min(line.length(), end));
  }

  static List<String> columns(String line) {
    List<String> cols = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    String body = line.stripTrailing();
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '"') {
        quoted = !quoted;
      } else if (c == ',' && !quoted) {
        cols.add(cur.toString());
        cur.setLength(0);
        continue;
      }
      cur.append(c);
    }
    cols.add(cur.toString());
    return cols;
  }
}
