package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AB-522 acceptance "no hardcoded rates left in the code": production Java in every module that
 * rates premiums must not contain decimal literals or build a BigDecimal from a literal. Rates live
 * in {@code pas.base_rates}, {@code pas.rating_parameters}, {@code pas.territory_factors} and
 * {@code pas.class_factors}. Equivalent grep, from pas-platform/:
 *
 * <pre>
 * grep -rnE '[0-9]+\.[0-9]+|new BigDecimal\(|BigDecimal\.valueOf\(' \
 *   pas-rating/src/main pas-policy-service/src/main/java pas-batch/src/main/java
 * </pre>
 *
 * (comments excluded).
 */
class NoHardcodedRatesTest {

  static final Pattern RATE_LITERAL =
      Pattern.compile(
          "(?<![\\w.])\\d+\\.\\d+|new\\s+BigDecimal\\s*\\(|BigDecimal\\.valueOf\\s*\\(");

  private static final List<String> SCANNED =
      List.of(
          "pas-rating/src/main/java",
          "pas-policy-service/src/main/java",
          "pas-batch/src/main/java");

  @Test
  void noRateLiteralsInProductionCode() throws IOException {
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
    List<String> hits = new ArrayList<>();
    for (String dir : SCANNED) {
      Path base = root.resolve(dir);
      assertThat(base).as("scanned source root").isDirectory();
      try (Stream<Path> files = Files.walk(base)) {
        files.filter(p -> p.toString().endsWith(".java")).forEach(p -> scan(root, p, hits));
      }
    }
    assertThat(hits).as("hardcoded rates; move them to the rating reference tables").isEmpty();
  }

  @Test
  void detectsTheAb520HardcodedCalculator() {
    String old =
        """
        private static final BigDecimal TAX_RATE = new BigDecimal("0.0350");
        BigDecimal x = y.multiply(BigDecimal.valueOf(25));
        double surcharge = 25.00;
        """;
    assertThat(findings(old)).hasSize(3);
    assertThat(findings("// rate was 0.0350\n/* 25.00 */ int coverageSeq = 1;")).isEmpty();
  }

  private static void scan(Path root, Path file, List<String> hits) {
    try {
      for (String hit : findings(Files.readString(file))) {
        hits.add(root.relativize(file) + ": " + hit);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<String> findings(String source) {
    String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    List<String> out = new ArrayList<>();
    for (String line : code.split("\\R")) {
      if (RATE_LITERAL.matcher(line).find()) {
        out.add(line.strip());
      }
    }
    return out;
  }
}
