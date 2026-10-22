package com.acme.insurance.pas.parity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public final class ParityHarness {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
  private static final ConcurrentMap<String, ProgramReport> REPORTS = new ConcurrentHashMap<>();

  private ParityHarness() {}

  public static Stream<DynamicTest> dynamicTests(ParityAdapter<?, ?> adapter) {
    Path root = locateRoot();
    try {
      KnownDifferences knownDifferences = KnownDifferences.load(root);
      List<String> validationErrors = knownDifferences.validate();
      if (!validationErrors.isEmpty()) {
        throw new IllegalStateException(String.join("; ", validationErrors));
      }
      List<Path> fixtures;
      try (Stream<Path> fixtureStream =
          Files.list(root.resolve("parity/fixtures").resolve(adapter.program()))) {
        fixtures =
            fixtureStream
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
      }
      if (fixtures.isEmpty()) {
        throw new IllegalStateException("No fixtures found for " + adapter.program());
      }
      String reportKey = reportKey(adapter.program(), adapter.scope());
      ProgramReport report = REPORTS.computeIfAbsent(reportKey, ignored -> new ProgramReport());
      Stream<DynamicTest> fixtureTests =
          fixtures.stream()
              .map(
                  fixture ->
                      DynamicTest.dynamicTest(
                          fixtureId(fixture),
                          () ->
                              executeFixture(
                                  root, adapter, knownDifferences, fixture, reportKey, report)));
      DynamicTest usedTest =
          DynamicTest.dynamicTest(
              "known-differences-are-used",
              () ->
                  verifyKnownDifferencesUsed(
                      adapter.program(), adapter.scope(), knownDifferences, report));
      return Stream.concat(fixtureTests, Stream.of(usedTest));
    } catch (IOException error) {
      throw new IllegalStateException(
          "Unable to load parity fixtures for " + adapter.program(), error);
    }
  }

  private static void executeFixture(
      Path root,
      ParityAdapter<?, ?> adapter,
      KnownDifferences knownDifferences,
      Path fixturePath,
      String reportKey,
      ProgramReport report)
      throws IOException {
    String fixtureId = fixtureId(fixturePath);
    JsonNode fixture = MAPPER.readTree(fixturePath.toFile());
    JsonNode spec =
        MAPPER.readTree(
            root.resolve("parity/programs")
                .resolve(adapter.program())
                .resolve("spec.json")
                .toFile());
    JsonNode inputValue = merge(spec.path("defaults"), fixture.path("input"));
    if (spec.has("seedDefaults") && inputValue.isObject()) {
      // Working-storage seeds model uninitialised COBOL fields; adapters whose Java contract
      // takes them as input (e.g. POLEND's endorsement type, Q-12) read them as "seed".
      ((ObjectNode) inputValue).set("seed", merge(spec.path("seedDefaults"), fixture.path("seed")));
    }
    Object input = MAPPER.treeToValue(inputValue, adapter.inputType());
    JsonNode actualOutputs = MAPPER.valueToTree(runAdapter(adapter, input));
    ObjectNode actual = MAPPER.createObjectNode().set("outputs", actualOutputs);
    JsonNode golden =
        MAPPER.readTree(
            root.resolve("parity/golden")
                .resolve(adapter.program())
                .resolve(fixtureId + ".json")
                .toFile());
    JsonNode expected = golden.path("outputs");
    List<GoldenComparator.Difference> differences =
        GoldenComparator.compare(expected, actualOutputs);
    List<KnownDifferences.Entry> fixtureEntries =
        knownDifferences.forFixture(adapter.program(), adapter.scope(), fixtureId);
    Set<KnownDifferences.Entry> javaValueMismatches =
        fixtureEntries.stream()
            .filter(entry -> !javaValueMatchesAtField(entry, actual))
            .collect(java.util.stream.Collectors.toSet());
    List<GoldenComparator.Difference> uncovered = new ArrayList<>();
    List<AppliedDifference> applied = new ArrayList<>();
    for (GoldenComparator.Difference difference : differences) {
      KnownDifferences.Entry entry =
          fixtureEntries.stream()
              .filter(candidate -> KnownDifferences.covers(candidate.field(), difference.path()))
              .findFirst()
              .orElse(null);
      if (entry == null) {
        uncovered.add(difference);
        continue;
      }
      if (javaValueMismatches.contains(entry)) {
        uncovered.add(difference);
        continue;
      }
      applied.add(new AppliedDifference(entry, difference));
    }
    String status = uncovered.isEmpty() ? (applied.isEmpty() ? "pass" : "known") : "fail";
    report.record(
        fixtureId,
        status,
        uncovered.stream().map(difference -> new ReportDifference(fixtureId, difference)).toList(),
        applied);
    writeReport(root);
    if (!uncovered.isEmpty()) {
      throw new AssertionError(
          "Uncovered parity differences for "
              + adapter.program()
              + "/"
              + adapter.scope()
              + "/"
              + fixtureId
              + ":\n"
              + renderDifferences(uncovered));
    }
  }

  @SuppressWarnings("unchecked")
  private static Object runAdapter(ParityAdapter<?, ?> adapter, Object input) {
    return ((ParityAdapter<Object, Object>) adapter).run(input);
  }

  private static void verifyKnownDifferencesUsed(
      String program, String scope, KnownDifferences knownDifferences, ProgramReport report)
      throws IOException {
    Set<KnownDifferences.Entry> used = report.usedEntries();
    List<KnownDifferences.Entry> stale =
        knownDifferences.entries().stream()
            .filter(entry -> entry.program().equals(program))
            .filter(entry -> entry.scope().equals(scope))
            .filter(entry -> !used.contains(entry))
            .toList();
    if (!stale.isEmpty()) {
      String message =
          "Stale known differences for "
              + program
              + " ["
              + scope
              + "]"
              + ": "
              + stale.stream().map(entry -> entry.fixture() + " " + entry.field()).toList();
      report.recordFailure("known-differences-are-used", message);
      writeReport(locateRoot());
      throw new AssertionError(message);
    }
    report.record("known-differences-are-used", "pass", List.of(), List.of());
    writeReport(locateRoot());
  }

  public static Path locateRoot() {
    String configured = System.getProperty("pas.parity.root");
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured).toAbsolutePath().normalize();
    }
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("parity/known-differences.yaml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Unable to locate parity/known-differences.yaml");
  }

  private static String fixtureId(Path fixture) {
    Path fileName = fixture.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException("Fixture path has no file name: " + fixture);
    }
    String name = fileName.toString();
    return name.substring(0, name.length() - ".json".length());
  }

  private static JsonNode merge(JsonNode base, JsonNode override) {
    if (override == null || override.isMissingNode()) {
      return base.deepCopy();
    }
    if (!base.isObject() || !override.isObject()) {
      return override.deepCopy();
    }
    ObjectNode merged = base.deepCopy();
    override
        .fields()
        .forEachRemaining(
            entry ->
                merged.set(
                    entry.getKey(),
                    merge(
                        base.has(entry.getKey()) ? base.get(entry.getKey()) : entry.getValue(),
                        entry.getValue())));
    return merged;
  }

  private static JsonNode nodeAtPath(JsonNode value, String path) {
    JsonNode current = value;
    int index = 0;
    while (index < path.length()) {
      if (path.charAt(index) == '.') {
        index++;
        continue;
      }
      if (path.charAt(index) == '[') {
        int end = path.indexOf(']', index);
        current = current.path(Integer.parseInt(path.substring(index + 1, end)));
        index = end + 1;
        continue;
      }
      int end = index;
      while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
        end++;
      }
      current = current.path(path.substring(index, end));
      index = end;
    }
    return current;
  }

  static boolean javaValueMatchesAtField(KnownDifferences.Entry entry, JsonNode actual) {
    JsonNode required = MAPPER.valueToTree(entry.javaValue());
    JsonNode actualValue = nodeAtPath(actual, entry.field());
    return GoldenComparator.compare(required, actualValue).isEmpty();
  }

  private static String reportKey(String program, String scope) {
    return program + " [" + scope + "]";
  }

  private static void writeReport(Path root) throws IOException {
    Path reportDir = Path.of(System.getProperty("user.dir")).resolve("target");
    String registerLink =
        reportDir
            .relativize(root.resolve("docs/modernization/quirk-register.md"))
            .toString()
            .replace('\\', '/');
    StringBuilder markdown = new StringBuilder("# Parity report\n");
    for (String programAndScope : REPORTS.keySet().stream().sorted().toList()) {
      ProgramReport report = REPORTS.get(programAndScope);
      markdown.append("\n## ").append(programAndScope).append("\n\n");
      markdown
          .append("| Pass | Fail | Pass with known difference |\n")
          .append("| ---: | ---: | ---: |\n")
          .append("| ")
          .append(report.count("pass"))
          .append(" | ")
          .append(report.count("fail"))
          .append(" | ")
          .append(report.count("known"))
          .append(" |\n");
      markdown.append("\n### Failing differences\n\n");
      if (report.failures().isEmpty()) {
        markdown.append("None.\n");
      } else {
        markdown
            .append("| Fixture | Path | Golden | Java |\n")
            .append("| --- | --- | --- | --- |\n");
        for (ReportDifference failure : report.failures()) {
          markdown
              .append("| ")
              .append(failure.fixture())
              .append(" | `")
              .append(failure.difference().path())
              .append("` | `")
              .append(jsonValue(failure.difference().expected()))
              .append("` | `")
              .append(jsonValue(failure.difference().actual()))
              .append("` |\n");
        }
        for (String message : report.failureMessages()) {
          markdown.append("\n").append(message).append("\n");
        }
      }
      markdown.append("\n### Applied known differences\n\n");
      if (report.applied().isEmpty()) {
        markdown.append("None.\n");
      } else {
        markdown
            .append("| Fixture | Path | Quirk | Reason | Register |\n")
            .append("| --- | --- | --- | --- | --- |\n");
        for (AppliedDifference applied : report.applied()) {
          markdown
              .append("| ")
              .append(applied.entry().fixture())
              .append(" | `")
              .append(applied.difference().path())
              .append("` | ")
              .append(applied.entry().quirk())
              .append(" | ")
              .append(applied.entry().reason())
              .append(" | [")
              .append(applied.entry().quirk())
              .append("](")
              .append(registerLink)
              .append("#")
              .append(applied.entry().quirk().toLowerCase())
              .append(") |\n");
        }
      }
    }
    Path reportPath = reportDir.resolve("parity-report.md");
    Files.createDirectories(reportDir);
    Files.writeString(reportPath, markdown);
  }

  private static String renderDifferences(List<GoldenComparator.Difference> differences) {
    return differences.stream()
        .map(
            difference ->
                difference.path()
                    + ": golden="
                    + jsonValue(difference.expected())
                    + ", Java="
                    + jsonValue(difference.actual()))
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  private static String jsonValue(JsonNode value) {
    return value == null ? "null" : value.toString();
  }

  private static final class ProgramReport {
    private final ConcurrentMap<String, String> statuses = new ConcurrentHashMap<>();
    private final List<ReportDifference> failures = new ArrayList<>();
    private final List<AppliedDifference> applied = new ArrayList<>();
    private final List<String> failureMessages = new ArrayList<>();

    synchronized void record(
        String id,
        String status,
        List<ReportDifference> newFailures,
        List<AppliedDifference> newApplied) {
      statuses.put(id, status);
      failures.addAll(newFailures);
      applied.addAll(newApplied);
    }

    synchronized void recordFailure(String id, String message) {
      statuses.put(id, "fail");
      failureMessages.add(message);
    }

    long count(String status) {
      return statuses.values().stream().filter(status::equals).count();
    }

    synchronized List<ReportDifference> failures() {
      return List.copyOf(failures);
    }

    synchronized List<AppliedDifference> applied() {
      return List.copyOf(applied);
    }

    synchronized List<String> failureMessages() {
      return List.copyOf(failureMessages);
    }

    synchronized Set<KnownDifferences.Entry> usedEntries() {
      Set<KnownDifferences.Entry> entries = new HashSet<>();
      applied.forEach(difference -> entries.add(difference.entry()));
      return entries;
    }
  }

  private record AppliedDifference(
      KnownDifferences.Entry entry, GoldenComparator.Difference difference) {}

  private record ReportDifference(String fixture, GoldenComparator.Difference difference) {}
}
