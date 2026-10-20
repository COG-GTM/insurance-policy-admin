package com.acme.insurance.pas.common.copybook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Parses the real cobol/copybooks sources so the Java layouts cannot drift from them. */
class PasCopybooksDriftTest {

  private static final Pattern ITEM =
      Pattern.compile(
          "^\\s*(\\d{2})\\s+([A-Z0-9-]+)(?:\\s+PIC\\s+(S?)(X|9)\\((\\d+)\\)(?:V(9+|9\\((\\d+)\\)))?)?"
              + "(\\s+COMP-3)?\\s*\\.");

  @ParameterizedTest
  @ValueSource(strings = {"POLICY-RECORD", "COVERAGE-RECORD", "PREMIUM-RECORD", "CUSTOMER-RECORD"})
  void layoutMatchesCopybookSource(String copybook) throws IOException {
    RecordLayout layout =
        switch (copybook) {
          case "POLICY-RECORD" -> PasCopybooks.POLICY_RECORD;
          case "COVERAGE-RECORD" -> PasCopybooks.COVERAGE_RECORD;
          case "PREMIUM-RECORD" -> PasCopybooks.PREMIUM_RECORD;
          default -> PasCopybooks.CUSTOMER_RECORD;
        };
    List<String> expected = new ArrayList<>();
    int lrecl = 0;
    for (String line : Files.readAllLines(copybookDir().resolve(copybook + ".cpy"))) {
      String code = line.length() > 72 ? line.substring(0, 72) : line;
      if (code.length() > 6 && code.charAt(6) == '*') {
        continue;
      }
      Matcher m = ITEM.matcher(code.length() > 7 ? code.substring(7) : "");
      if (!m.find() || m.group(3) == null && m.group(4) == null) {
        continue;
      }
      int len = Integer.parseInt(m.group(5));
      int scale =
          m.group(6) == null
              ? 0
              : m.group(7) != null ? Integer.parseInt(m.group(7)) : m.group(6).length();
      boolean packed = m.group(8) != null;
      lrecl += packed ? (len + scale) / 2 + 1 : len + scale;
      String kind;
      if ("FILLER".equals(m.group(2))) {
        kind = "FILLER";
      } else if ("X".equals(m.group(4))) {
        kind = "ALPHANUMERIC";
      } else if (packed) {
        kind = "PACKED";
      } else if (len == 8 && scale == 0 && m.group(2).matches(".*(DATE|DOB|UPDATED)$")) {
        kind = "DATE_YYYYMMDD";
      } else {
        kind = "ZONED";
      }
      expected.add(
          m.group(2)
              + ":"
              + kind
              + ":"
              + (len + scale)
              + ":"
              + scale
              + ":"
              + !m.group(3).isEmpty());
    }
    List<String> actual =
        layout.fields().stream()
            .map(
                f ->
                    f.name()
                        + ":"
                        + f.kind()
                        + ":"
                        + f.digits()
                        + ":"
                        + f.scale()
                        + ":"
                        + f.signed())
            .toList();
    assertThat(actual).containsExactlyElementsOf(expected);
    assertThat(layout.length()).isEqualTo(lrecl);
  }

  private static Path copybookDir() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("cobol/copybooks"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repository root with cobol/copybooks").isNotNull();
    return dir.resolve("cobol/copybooks");
  }
}
