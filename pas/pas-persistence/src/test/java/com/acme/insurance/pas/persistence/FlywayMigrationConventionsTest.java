package com.acme.insurance.pas.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Flyway conventions for epic AB-516: vendor locations, naming and per-ticket version ranges. */
class FlywayMigrationConventionsTest {

  static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  static final Set<String> VENDORS = Set.of("postgresql", "db2");
  static final Pattern NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");

  /** Version ranges allocated per ticket by the epic; mirrored in pas-persistence/README.md. */
  static final Map<String, int[]> RANGES =
      Map.ofEntries(
          Map.entry("AB-519", new int[] {1, 9}),
          Map.entry("AB-521", new int[] {10, 19}),
          Map.entry("AB-522", new int[] {20, 29}),
          Map.entry("AB-523", new int[] {30, 39}),
          Map.entry("AB-524", new int[] {40, 49}),
          Map.entry("AB-525", new int[] {50, 59}),
          Map.entry("AB-526", new int[] {60, 69}),
          Map.entry("AB-527", new int[] {70, 79}),
          Map.entry("AB-528", new int[] {80, 89}),
          Map.entry("AB-529", new int[] {90, 99}),
          Map.entry("AB-530", new int[] {100, 109}));

  @Test
  void migrationsLiveInAVendorLocationAndFollowTheNamingRule() throws IOException {
    List<String> problems = new ArrayList<>();
    for (Path file : migrationFiles()) {
      Path relative = MIGRATIONS.relativize(file);
      if (relative.getNameCount() != 2 || !VENDORS.contains(relative.getName(0).toString())) {
        problems.add(relative + ": must be in db/migration/{postgresql,db2}/");
      }
      if (!NAME.matcher(file.getFileName().toString()).matches()) {
        problems.add(relative + ": must be named V<version>__<lower_snake_description>.sql");
      }
    }
    assertThat(problems).isEmpty();
  }

  @Test
  void everyVersionIsInsideAnAllocatedTicketRangeAndUniquePerVendor() throws IOException {
    List<String> problems = new ArrayList<>();
    Map<String, String> seen = new HashMap<>();
    for (Path file : migrationFiles()) {
      Matcher m = NAME.matcher(file.getFileName().toString());
      if (!m.matches()) {
        continue;
      }
      int version = Integer.parseInt(m.group(1));
      String vendor = file.getParent().getFileName().toString();
      if (ticketFor(version) == null) {
        problems.add(file.getFileName() + ": V" + version + " is outside every allocated range");
      }
      String previous = seen.put(vendor + ":" + version, file.getFileName().toString());
      if (previous != null) {
        problems.add(vendor + ": V" + version + " used twice (" + previous + ")");
      }
    }
    assertThat(problems).isEmpty();
  }

  @Test
  void baselineMigrationIsOwnedByAb519() {
    assertThat(ticketFor(1)).isEqualTo("AB-519");
    assertThat(ticketFor(10)).isEqualTo("AB-521");
    assertThat(ticketFor(110)).isNull();
  }

  static String ticketFor(int version) {
    return RANGES.entrySet().stream()
        .filter(e -> version >= e.getValue()[0] && version <= e.getValue()[1])
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  static List<Path> migrationFiles() throws IOException {
    try (Stream<Path> files = Files.walk(MIGRATIONS)) {
      return files.filter(Files::isRegularFile).sorted().toList();
    }
  }
}
