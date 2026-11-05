package com.acme.insurance.pas.parity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares a Java run with the COBOL golden run. Numbers are compared as decimals, so any
 * difference of a cent (or of one unit of the field's scale) fails; text is compared exactly after
 * right-trimming, like a COBOL PIC X field. Probes are compared over the union of both sides, so a
 * probe COBOL recorded but Java leaves out is a difference unless the suite lists it in {@code
 * ignored_probes}.
 */
public final class ParityComparator {

  /** One difference at a result path such as {@code effects[0].numbers.TOTAL_PREMIUM}. */
  public record Difference(String path, String expected, String actual) {}

  private ParityComparator() {}

  public static List<Difference> compare(RunResult golden, RunResult actual) {
    return compare(golden, actual, Set.of());
  }

  public static List<Difference> compare(
      RunResult golden, RunResult actual, Set<String> ignoredProbes) {
    List<Difference> differences = new ArrayList<>();
    check(differences, "outcome.status", golden.status(), actual.status());
    check(
        differences,
        "outcome.return_code",
        String.valueOf(golden.returnCode()),
        String.valueOf(actual.returnCode()));
    check(differences, "outcome.abend_code", golden.abendCode(), actual.abendCode());
    int expectedCount = golden.effects().size();
    int actualCount = actual.effects().size();
    check(differences, "effects.count", String.valueOf(expectedCount), String.valueOf(actualCount));
    for (int i = 0; i < Math.min(expectedCount, actualCount); i++) {
      compareEffect(
          differences,
          "effects[" + i + "]",
          golden.effects().get(i),
          actual.effects().get(i),
          ignoredProbes);
    }
    return differences;
  }

  private static void compareEffect(
      List<Difference> differences,
      String prefix,
      Effect expected,
      Effect actual,
      Set<String> ignoredProbes) {
    check(differences, prefix + ".op", expected.op(), actual.op());
    check(
        differences,
        prefix + ".sqlcode",
        Objects.toString(expected.sqlcode(), null),
        Objects.toString(actual.sqlcode(), null));
    for (String name : union(expected.text(), actual.text())) {
      check(
          differences,
          prefix + ".text." + name,
          rtrim(expected.text().get(name)),
          rtrim(actual.text().get(name)));
    }
    for (String name : union(expected.numbers(), actual.numbers())) {
      checkNumber(
          differences,
          prefix + ".numbers." + name,
          expected.numbers().get(name),
          actual.numbers().get(name));
    }
    TreeSet<String> probes = union(expected.probes(), actual.probes());
    probes.removeAll(ignoredProbes);
    for (String name : probes) {
      checkNumber(
          differences,
          prefix + ".probes." + name,
          expected.probes().get(name),
          actual.probes().get(name));
    }
  }

  private static TreeSet<String> union(Map<String, String> left, Map<String, String> right) {
    TreeSet<String> names = new TreeSet<>(left.keySet());
    names.addAll(right.keySet());
    return names;
  }

  private static void check(
      List<Difference> differences, String path, String expected, String actual) {
    if (!Objects.equals(expected, actual)) {
      differences.add(new Difference(path, expected, actual));
    }
  }

  private static void checkNumber(
      List<Difference> differences, String path, String expected, String actual) {
    if (expected == null || actual == null) {
      check(differences, path, expected, actual);
    } else if (new BigDecimal(expected).compareTo(new BigDecimal(actual)) != 0) {
      differences.add(new Difference(path, expected, actual));
    }
  }

  private static String rtrim(String value) {
    return value == null ? null : value.stripTrailing();
  }
}
