package com.acme.insurance.pas.copybook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PasCopybooksDriftTest {
  private static final Pattern FIELD =
      Pattern.compile("^(?:05|10)\\s+([A-Z0-9-]+)\\s+PIC\\s+([^\\s.]+)(?:\\s+(COMP-3))?\\.");
  private static final Pattern DIGIT_PICTURE = Pattern.compile("9(?:\\((\\d+)\\))?");

  @Test
  void layoutsStayInSyncWithLegacyCopybooks() throws IOException {
    Path root = repositoryRoot(Path.of("").toAbsolutePath());
    Assumptions.assumeTrue(
        Files.isDirectory(root.resolve("cobol/copybooks")),
        "legacy copybooks decommissioned (AB-530); layouts frozen in PasCopybooks");
    verify(root.resolve("cobol/copybooks/POLICY-RECORD.cpy"), PasCopybooks.POLICY_RECORD);
    verify(root.resolve("cobol/copybooks/COVERAGE-RECORD.cpy"), PasCopybooks.COVERAGE_RECORD);
    verify(root.resolve("cobol/copybooks/CUSTOMER-RECORD.cpy"), PasCopybooks.CUSTOMER_RECORD);
    verify(root.resolve("cobol/copybooks/PREMIUM-RECORD.cpy"), PasCopybooks.PREMIUM_RECORD);
  }

  private static void verify(Path copybook, RecordLayout layout) throws IOException {
    List<DriftField> parsed = new ArrayList<>();
    int offset = 0;
    for (String sourceLine : Files.readAllLines(copybook)) {
      if (sourceLine.length() <= 7 || sourceLine.charAt(6) == '*' || sourceLine.charAt(6) == '/') {
        continue;
      }
      String line = sourceLine.substring(7, Math.min(72, sourceLine.length())).trim();
      Matcher matcher = FIELD.matcher(line);
      if (!matcher.find()) {
        continue;
      }
      String fieldName = matcher.group(1);
      String pic = matcher.group(2);
      int length = fieldLength(pic, matcher.group(3) != null);
      parsed.add(new DriftField(fieldName, offset, length));
      offset += length;
    }

    List<DriftField> expected =
        layout.fields().stream()
            .map(
                field ->
                    new DriftField(
                        field.type() instanceof FieldType.Filler ? "FILLER" : field.name(),
                        field.offset(),
                        field.type().byteLength()))
            .toList();
    assertThat(parsed).as(copybook.getFileName().toString()).containsExactlyElementsOf(expected);
    assertThat(offset).isEqualTo(layout.length());
  }

  private static int fieldLength(String pic, boolean packed) {
    if (pic.startsWith("X(")) {
      return Integer.parseInt(pic.substring(2, pic.length() - 1));
    }
    Matcher matcher = DIGIT_PICTURE.matcher(pic);
    int digits = 0;
    while (matcher.find()) {
      digits += matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1));
    }
    return packed ? digits / 2 + 1 : digits;
  }

  private static Path repositoryRoot(Path start) {
    Path current = start;
    while (current != null) {
      if (Files.exists(current.resolve(".git"))
          || Files.isDirectory(current.resolve("pas-platform"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Repository root with cobol/copybooks was not found");
  }

  private record DriftField(String name, int offset, int length) {}
}
