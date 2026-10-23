package com.acme.insurance.pas.batch.extract.compare;

import com.acme.insurance.pas.batch.extract.layout.ExtractLayout;
import com.acme.insurance.pas.batch.extract.layout.ExtractLayouts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class ExtractCompare {
  private static final Pattern DATED_MONTHLY_NAME =
      Pattern.compile("exposure_monthly\\.D\\d{6}\\.csv");

  private ExtractCompare() {}

  public static void main(String[] args) {
    int exit = compare(args, System.out, System.err);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  public static int compare(String[] args, java.io.PrintStream out, java.io.PrintStream error) {
    if (args.length != 2) {
      error.println("Usage: ExtractCompare EXPECTED_DIR ACTUAL_DIR");
      return 2;
    }
    Path expected = Path.of(args[0]);
    Path actual = Path.of(args[1]);
    if (!Files.isDirectory(expected) || !Files.isDirectory(actual)) {
      error.println("Both arguments must be directories");
      return 2;
    }
    try {
      Map<String, String> expectedFiles = names(expected);
      Map<String, String> actualFiles = names(actual);
      boolean different = false;
      for (String name : expectedFiles.keySet().stream().sorted().toList()) {
        if (!actualFiles.containsKey(name)) {
          out.println("MISSING " + name);
          different = true;
          continue;
        }
        byte[] left = Files.readAllBytes(expected.resolve(expectedFiles.get(name)));
        byte[] right = Files.readAllBytes(actual.resolve(actualFiles.get(name)));
        ExtractLayout layout = layoutOrNull(name);
        if (layout != null && hasLayoutMismatch(left, layout)) {
          out.println("LAYOUT MISMATCH expected " + name + ": record length or separator differs");
          different = true;
        }
        if (layout != null && hasLayoutMismatch(right, layout)) {
          out.println("LAYOUT MISMATCH actual " + name + ": record length or separator differs");
          different = true;
        }
        if (Arrays.equals(left, right)) {
          out.println("MATCH " + name + " sha256=" + sha256(left));
        } else {
          different = true;
          reportDifference(name, left, right, layout, out);
        }
      }
      for (String name : actualFiles.keySet().stream().sorted().toList()) {
        if (!expectedFiles.containsKey(name)) {
          out.println("EXTRA " + name);
          different = true;
        }
      }
      return different ? 1 : 0;
    } catch (DuplicateFileNameException exception) {
      error.println("Ambiguous extract file names: " + exception.getMessage());
      return 2;
    } catch (IOException | RuntimeException exception) {
      error.println("Unable to compare extract files: " + exception.getClass().getSimpleName());
      return 2;
    }
  }

  public static java.util.List<String> differences(Path expected, Path actual) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    int exit =
        compare(
            new String[] {expected.toString(), actual.toString()},
            new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8),
            new PrintStream(error, true, java.nio.charset.StandardCharsets.UTF_8));
    if (exit == 2) {
      throw new IllegalArgumentException(
          "Unable to compare extract directories: "
              + error.toString(java.nio.charset.StandardCharsets.UTF_8).strip());
    }
    return output
        .toString(java.nio.charset.StandardCharsets.UTF_8)
        .lines()
        .filter(line -> !(line.startsWith("MATCH ") || line.isBlank()))
        .toList();
  }

  private static Map<String, String> names(Path directory) throws IOException {
    Map<String, String> names = new LinkedHashMap<>();
    java.util.List<String> fileNames;
    try (var files = Files.list(directory)) {
      fileNames =
          files
              .filter(Files::isRegularFile)
              .map(path -> path.getFileName().toString())
              .filter(name -> !name.endsWith(".sha256") && !name.endsWith(".done"))
              .sorted()
              .toList();
    }
    for (String fileName : fileNames) {
      String comparisonName = comparisonName(fileName);
      String previous = names.putIfAbsent(comparisonName, fileName);
      if (previous != null) {
        throw new DuplicateFileNameException(
            "'" + previous + "' and '" + fileName + "' both map to '" + comparisonName + "'");
      }
    }
    return names;
  }

  private static String comparisonName(String fileName) {
    return DATED_MONTHLY_NAME.matcher(fileName).matches() ? "exposure_monthly.csv" : fileName;
  }

  private static void reportDifference(
      String name, byte[] expected, byte[] actual, ExtractLayout layout, java.io.PrintStream out) {
    int sharedLength = Math.min(expected.length, actual.length);
    int offset = 0;
    while (offset < sharedLength && expected[offset] == actual[offset]) {
      offset++;
    }
    out.print("DIFF " + name + " first differing byte offset " + offset);
    if (layout != null) {
      if (layout.kind() == ExtractLayout.Kind.FIXED) {
        int stride = layout.maximumRecordLength() + layout.recordSeparator().length();
        int recordNumber = offset / stride + 1;
        int recordOffset = offset % stride;
        var field =
            layout.recordLayout().fields().stream()
                .filter(candidate -> recordOffset >= candidate.offset())
                .filter(
                    candidate -> recordOffset < candidate.offset() + candidate.type().byteLength())
                .findFirst();
        if (field.isPresent()) {
          var descriptor = field.get();
          out.print(
              ", record "
                  + recordNumber
                  + ", byte offset within record "
                  + recordOffset
                  + ", field "
                  + descriptor.name()
                  + " (field offset "
                  + (descriptor.offset() + 1)
                  + ", width "
                  + descriptor.type().byteLength()
                  + ")");
        } else {
          out.print(", record " + recordNumber + ", byte offset within record " + recordOffset);
        }
      } else {
        CsvLocation location = csvLocation(expected, actual, offset);
        out.print(
            ", line "
                + location.line()
                + ", column "
                + ExtractLayouts.exposureColumns().get(location.column()));
      }
    }
    if (expected.length != actual.length) {
      out.print(", length expected " + expected.length + " actual " + actual.length);
    }
    out.println();
  }

  private static CsvLocation csvLocation(byte[] expected, byte[] actual, int offset) {
    byte[] source = offset < expected.length ? expected : actual;
    int recordNumber = 1;
    int recordStart = 0;
    boolean quoted = false;
    for (int index = 0; index < Math.min(offset, source.length); index++) {
      byte value = source[index];
      if (value == '"') {
        if (quoted && index + 1 < source.length && source[index + 1] == '"') {
          index++;
        } else {
          quoted = !quoted;
        }
      } else if (value == '\n' && !quoted) {
        recordNumber++;
        recordStart = index + 1;
      }
    }
    int column = 0;
    quoted = false;
    for (int index = recordStart; index < Math.min(offset, source.length); index++) {
      byte value = source[index];
      if (value == '"') {
        if (quoted && index + 1 < source.length && source[index + 1] == '"') {
          index++;
        } else {
          quoted = !quoted;
        }
      } else if (value == ',' && !quoted) {
        column++;
      }
    }
    return new CsvLocation(recordNumber, Math.min(column, 8));
  }

  private static boolean hasLayoutMismatch(byte[] bytes, ExtractLayout layout) {
    if (layout.kind() == ExtractLayout.Kind.FIXED) {
      int stride = layout.maximumRecordLength() + layout.recordSeparator().length();
      if (bytes.length == 0) {
        return false;
      }
      if (bytes.length % stride != 0) {
        return true;
      }
      for (int offset = layout.maximumRecordLength(); offset < bytes.length; offset += stride) {
        if (bytes[offset] != '\r' || bytes[offset + 1] != '\n') {
          return true;
        }
      }
      return false;
    }
    if (bytes.length == 0) {
      return false;
    }
    int recordLength = 0;
    boolean quoted = false;
    for (int index = 0; index < bytes.length; index++) {
      byte value = bytes[index];
      if (value == '"') {
        if (quoted && index + 1 < bytes.length && bytes[index + 1] == '"') {
          recordLength += 2;
          index++;
        } else {
          quoted = !quoted;
          recordLength++;
        }
      } else if (value == '\r' && index + 1 < bytes.length && bytes[index + 1] == '\n' && !quoted) {
        if (recordLength > layout.maximumRecordLength()) {
          return true;
        }
        recordLength = 0;
        index++;
      } else {
        recordLength++;
      }
      if (recordLength > layout.maximumRecordLength()) {
        return true;
      }
    }
    return quoted || recordLength != 0;
  }

  private static ExtractLayout layoutOrNull(String fileName) {
    try {
      return ExtractLayouts.byFileName(fileName);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static final class DuplicateFileNameException extends IOException {
    private DuplicateFileNameException(String message) {
      super(message);
    }
  }

  private record CsvLocation(int line, int column) {}
}
