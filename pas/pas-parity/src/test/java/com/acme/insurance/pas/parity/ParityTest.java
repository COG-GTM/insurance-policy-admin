package com.acme.insurance.pas.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.parity.ParityComparator.Difference;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every registered Java adapter against its suite's fixtures and compares the result with the
 * COBOL golden files ({@code make parity}). A difference fails the test unless a known difference
 * for a 'fix' quirk covers it; with {@code PARITY_REQUIRE_SIGNOFF=1} only approved ones count.
 */
class ParityTest {

  private static final ParityData DATA = ParityData.fromSystemProperty();
  private static final boolean REQUIRE_SIGN_OFF =
      "1".equals(System.getenv("PARITY_REQUIRE_SIGNOFF"))
          || Boolean.getBoolean("parity.requireSignOff");
  private static final List<String> REPORT = new ArrayList<>();

  @TestFactory
  Stream<DynamicNode> javaMatchesCobolGoldenFiles() {
    List<KnownDifference> known = DATA.knownDifferences();
    List<DynamicNode> suites = new ArrayList<>();
    for (JsonNode suite : DATA.suites()) {
      String name = suite.get("suite").asText();
      JsonNode adapterClass = suite.get("java_adapter");
      if (adapterClass == null || adapterClass.isNull()) {
        REPORT.add(
            "| "
                + name
                + " | pending adapter ("
                + suite.get("owner_ticket").asText()
                + ") | "
                + DATA.fixtures(suite).size()
                + " golden cases | - |");
        continue;
      }
      ProgramAdapter adapter = instantiate(adapterClass.asText());
      Set<String> ignoredProbes = new TreeSet<>();
      suite.path("ignored_probes").fieldNames().forEachRemaining(ignoredProbes::add);
      List<DynamicTest> tests = new ArrayList<>();
      for (FixtureCase fixture : DATA.fixtures(suite)) {
        tests.add(
            DynamicTest.dynamicTest(
                fixture.id(), () -> assertParity(name, fixture, adapter, known, ignoredProbes)));
      }
      REPORT.add(
          "| " + name + " | " + adapterClass.asText() + " | " + tests.size() + " cases | run |");
      suites.add(DynamicContainer.dynamicContainer(name, tests));
    }
    assertThat(suites).as("at least one suite is wired to a Java adapter").isNotEmpty();
    return suites.stream();
  }

  private static void assertParity(
      String suite,
      FixtureCase fixture,
      ProgramAdapter adapter,
      List<KnownDifference> known,
      Set<String> ignoredProbes) {
    RunResult golden = DATA.golden(suite, fixture.id());
    RunResult actual = adapter.run(fixture);
    List<String> unexplained = new ArrayList<>();
    for (Difference difference : ParityComparator.compare(golden, actual, ignoredProbes)) {
      Optional<KnownDifference> allowed =
          known.stream()
              .filter(kd -> kd.covers(suite, fixture.id(), difference.path()))
              .filter(kd -> kd.approved() || !REQUIRE_SIGN_OFF)
              .findFirst();
      if (allowed.isPresent()) {
        KnownDifference kd = allowed.get();
        REPORT.add(
            "| "
                + suite
                + "/"
                + fixture.id()
                + " | known difference "
                + kd.id()
                + " ("
                + kd.quirk()
                + ", sign-off "
                + kd.signOff()
                + ") | "
                + difference.path()
                + " | allowed |");
      } else {
        unexplained.add(
            difference.path()
                + ": COBOL="
                + difference.expected()
                + " Java="
                + difference.actual());
      }
    }
    assertThat(unexplained)
        .as(
            "%s/%s (%s) differs from the COBOL golden file:%n  %s",
            suite,
            fixture.id(),
            fixture.description(),
            unexplained.stream().collect(Collectors.joining(System.lineSeparator() + "  ")))
        .isEmpty();
  }

  private static ProgramAdapter instantiate(String className) {
    try {
      return Class.forName(className)
          .asSubclass(ProgramAdapter.class)
          .getDeclaredConstructor()
          .newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("java_adapter " + className + " cannot be created", e);
    }
  }

  @AfterAll
  static void writeReport() throws IOException {
    String target = System.getProperty("parity.report");
    if (target == null) {
      return;
    }
    List<String> lines = new ArrayList<>();
    lines.add("# Parity report (AB-520)");
    lines.add("");
    lines.add("| Suite / case | Adapter or known difference | Scope | Result |");
    lines.add("|---|---|---|---|");
    lines.addAll(REPORT);
    Path path = Path.of(target);
    Files.createDirectories(path.getParent());
    Files.write(path, lines);
  }
}
