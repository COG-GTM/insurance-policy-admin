package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AB-522 acceptance: no rate is hardcoded in the rating component. Rates, tax and surcharge come
 * from PASAPP.BASE_RATES / PASAPP.RATING_PARAMETERS; the neutral factor comes from pas-domain.
 * Fails on any decimal literal or {@code new BigDecimal(...)} in the production sources.
 */
class NoHardcodedRatesTest {

  private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
  private static final Pattern RATE_LITERAL =
      Pattern.compile("\\b\\d+\\.\\d+\\b|new\\s+BigDecimal\\s*\\(|BigDecimal\\.valueOf\\s*\\(");

  @Test
  void productionSourcesHoldNoRateLiterals() throws IOException {
    List<String> findings = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String code = COMMENTS.matcher(Files.readString(file)).replaceAll("");
        Matcher m = RATE_LITERAL.matcher(code);
        while (m.find()) {
          findings.add(file.getFileName() + ": " + m.group());
        }
      }
    }
    assertThat(findings).isEmpty();
  }
}
