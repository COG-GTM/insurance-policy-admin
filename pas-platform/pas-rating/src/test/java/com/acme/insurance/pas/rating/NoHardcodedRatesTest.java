package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NoHardcodedRatesTest {
  private static final Pattern DECIMAL = Pattern.compile("(?<![\\w.])\\d+\\.\\d+");
  private static final Pattern RATE_INTEGER =
      Pattern.compile("(?<![\\w.])(?:850|1200|5000|400|3500|1000|25|350|35)(?![\\w.])");
  private static final List<Pattern> FORBIDDEN =
      List.of(
          DECIMAL,
          Pattern.compile("BigDecimal\\.valueOf\\s*\\("),
          Pattern.compile("new\\s+BigDecimal\\s*\\("),
          RATE_INTEGER);

  @Test
  void productionSourcesContainNoHardcodedRates() throws IOException {
    Path sourceRoot = Path.of("src/main/java");
    try (Stream<Path> sources = Files.walk(sourceRoot)) {
      List<String> violations =
          sources
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".java"))
              .flatMap(path -> violations(path, read(path)).stream())
              .toList();
      assertThat(violations).isEmpty();
    }
  }

  @Test
  void scannerFlagsPlantedRateLiteralsAndBigDecimalConstruction() {
    assertThat(violations(Path.of("Planted.java"), "BigDecimal.valueOf(850); // 1.25")).hasSize(3);
  }

  private static List<String> violations(Path path, String source) {
    List<String> matches = new ArrayList<>();
    for (Pattern pattern : FORBIDDEN) {
      var matcher = pattern.matcher(source);
      while (matcher.find()) {
        matches.add(path + ": " + matcher.group());
      }
    }
    return matches;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
