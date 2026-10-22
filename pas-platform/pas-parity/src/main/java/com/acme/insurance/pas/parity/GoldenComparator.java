package com.acme.insurance.pas.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class GoldenComparator {
  private GoldenComparator() {}

  public static List<Difference> compare(JsonNode expected, JsonNode actual) {
    List<Difference> differences = new ArrayList<>();
    compareAt("outputs", expected, actual, differences);
    return List.copyOf(differences);
  }

  private static void compareAt(
      String path, JsonNode expected, JsonNode actual, List<Difference> differences) {
    if (expected == null || actual == null) {
      if (expected != actual) {
        differences.add(new Difference(path, expectedNode(expected), expectedNode(actual)));
      }
      return;
    }
    if (expected.isObject() && actual.isObject()) {
      TreeSet<String> keys = new TreeSet<>();
      expected.fieldNames().forEachRemaining(keys::add);
      actual.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        String childPath = path.isEmpty() ? key : path + "." + key;
        compareAt(childPath, expected.get(key), actual.get(key), differences);
      }
      return;
    }
    if (expected.isArray() && actual.isArray()) {
      int count = Math.max(expected.size(), actual.size());
      for (int index = 0; index < count; index++) {
        String childPath = path + "[" + index + "]";
        compareAt(
            childPath,
            index < expected.size() ? expected.get(index) : null,
            index < actual.size() ? actual.get(index) : null,
            differences);
      }
      return;
    }
    if (expected.isNumber() && actual.isNumber()) {
      if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
        differences.add(new Difference(path, expected, actual));
      }
      return;
    }
    if (expected.isTextual() && actual.isTextual()) {
      if (!expected.textValue().stripTrailing().equals(actual.textValue().stripTrailing())) {
        differences.add(new Difference(path, expected, actual));
      }
      return;
    }
    if (!expected.equals(actual)) {
      differences.add(new Difference(path, expected, actual));
    }
  }

  private static JsonNode expectedNode(JsonNode node) {
    return node == null ? MissingNode.getInstance() : node;
  }

  public record Difference(String path, JsonNode expected, JsonNode actual) {}
}
