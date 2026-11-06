package com.acme.insurance.pas.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Rules from the ADR 0001 consequences that are checked on the source of the new modules. */
class LegacyBoundaryRulesTest {

  /** pas/ (the reactor root); tests run with the module directory as working directory. */
  static final Path PAS_ROOT = Path.of("..").toAbsolutePath().normalize();

  static final Pattern CLAIMS_TABLE =
      Pattern.compile("(?i)\\b(ACMEINS\\s*\\.\\s*CLAIMS|(FROM|JOIN)\\s+\"?CLAIMS\"?)\\b");

  @Test
  void newCodeNeverReadsTheClaimsTableDirectly() {
    List<String> offenders =
        mainSources()
            .filter(file -> CLAIMS_TABLE.matcher(read(file)).find())
            .map(file -> PAS_ROOT.relativize(file).toString())
            .toList();
    assertThat(offenders)
        .as("Claims data goes through the agreed Claims interface (INT-03), not ACMEINS.CLAIMS")
        .isEmpty();
  }

  @Test
  void sourceScanFindsTheModules() {
    assertThat(mainSources().map(f -> PAS_ROOT.relativize(f).getName(0).toString()).distinct())
        .contains("pas-policy-service", "pas-batch", "pas-persistence");
  }

  static Stream<Path> mainSources() {
    try (Stream<Path> modules = Files.list(PAS_ROOT)) {
      return modules
          .filter(dir -> Files.isDirectory(dir.resolve("src/main")))
          .flatMap(dir -> walk(dir.resolve("src/main")))
          .filter(f -> f.toString().matches(".*\\.(java|sql|yml|yaml|properties|xml)$"))
          .toList()
          .stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Stream<Path> walk(Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      return files.filter(Files::isRegularFile).toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
