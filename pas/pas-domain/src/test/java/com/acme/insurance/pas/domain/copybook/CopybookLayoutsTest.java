package com.acme.insurance.pas.domain.copybook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Fails when CopybookLayouts drifts from the frozen copybooks in cobol/copybooks. */
class CopybookLayoutsTest {

  private static final Pattern ITEM =
      Pattern.compile("^(\\d\\d)\\s+([A-Z0-9-]+)(?:\\s+PIC\\s+(\\S+?))?(\\s+COMP-3)?\\s*\\.\\s*$");
  private static final Pattern X = Pattern.compile("X\\((\\d+)\\)");
  private static final Pattern NINE = Pattern.compile("9\\((\\d+)\\)");
  private static final Pattern PACKED = Pattern.compile("S9\\((\\d+)\\)V(9+|9\\((\\d+)\\))");

  static List<CopybookLayout> layouts() {
    return CopybookLayouts.ALL;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("layouts")
  void matchesTheLegacyCopybook(CopybookLayout layout) throws IOException {
    List<String> expected = parse(copybooks().resolve(layout.name() + ".cpy"));

    List<String> actual = layout.fields().stream().map(CopybookLayoutsTest::describe).toList();

    assertThat(actual).containsExactlyElementsOf(expected);
    assertThat(layout.length())
        .isEqualTo(layout.fields().stream().mapToInt(CopybookField::length).sum());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("layouts")
  void hasTheDocumentedLength(CopybookLayout layout) {
    int expected =
        switch (layout.name()) {
          case "POLICY-RECORD" -> 125;
          case "COVERAGE-RECORD" -> 124;
          case "PREMIUM-RECORD" -> 105;
          case "CUSTOMER-RECORD" -> 365;
          default -> throw new IllegalArgumentException(layout.name());
        };
    assertThat(layout.length()).isEqualTo(expected);
  }

  private static String describe(CopybookField field) {
    return switch (field) {
      case CopybookField.Alphanumeric a -> a.name() + " X(" + a.length() + ")";
      case CopybookField.Display d -> d.name() + " 9(" + d.digits() + ")";
      case CopybookField.Packed p ->
          p.name() + " S9(" + p.integerDigits() + ")V9(" + p.scale() + ") COMP-3";
      case CopybookField.Filler f -> "FILLER X(" + f.length() + ")";
    };
  }

  private static List<String> parse(Path copybook) throws IOException {
    List<String> items = new ArrayList<>();
    for (String raw : Files.readAllLines(copybook, StandardCharsets.UTF_8)) {
      if (raw.length() < 7 || raw.charAt(6) == '*') {
        continue;
      }
      String line = raw.substring(7, Math.min(raw.length(), 72)).trim().toUpperCase(Locale.ROOT);
      Matcher m = ITEM.matcher(line);
      if (!m.matches() || m.group(1).equals("88") || m.group(3) == null) {
        continue;
      }
      String name = m.group(2);
      String pic = m.group(3);
      Matcher packed = PACKED.matcher(pic);
      Matcher x = X.matcher(pic);
      Matcher nine = NINE.matcher(pic);
      if (m.group(4) != null && packed.matches()) {
        int scale =
            packed.group(3) != null ? Integer.parseInt(packed.group(3)) : packed.group(2).length();
        items.add(name + " S9(" + Integer.parseInt(packed.group(1)) + ")V9(" + scale + ") COMP-3");
      } else if (x.matches()) {
        items.add(name + " X(" + Integer.parseInt(x.group(1)) + ")");
      } else if (nine.matches()) {
        items.add(name + " 9(" + Integer.parseInt(nine.group(1)) + ")");
      } else {
        throw new IllegalStateException("unsupported PIC " + pic + " in " + copybook);
      }
    }
    return items;
  }

  private static Path copybooks() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("cobol/copybooks"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repository root containing cobol/copybooks").isNotNull();
    return dir.resolve("cobol/copybooks");
  }
}
