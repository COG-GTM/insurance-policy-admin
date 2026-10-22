package com.acme.insurance.pas.parity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnownDifferences {
  private static final Pattern QUIRK_ID = Pattern.compile("Q-\\d{2}");
  private static final Pattern QUIRK_HEADING = Pattern.compile("(?m)^### (Q-\\d{2})\\b");

  private final Path root;
  private final List<Entry> entries;

  public KnownDifferences(Path root, List<Entry> entries) {
    this.root = root;
    this.entries = List.copyOf(entries);
  }

  public static KnownDifferences load(Path root) throws IOException {
    Path path = root.resolve("parity/known-differences.yaml");
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    List<Map<String, Object>> values =
        mapper.readValue(Files.readString(path), new TypeReference<>() {});
    List<Entry> entries = new ArrayList<>();
    for (Map<String, Object> value : values) {
      entries.add(
          new Entry(
              stringValue(value, "program"),
              stringValue(value, "fixture"),
              stringValue(value, "field"),
              stringValue(value, "quirk"),
              stringValue(value, "reason"),
              value.get("javaValue")));
    }
    return new KnownDifferences(root, entries);
  }

  public List<Entry> entries() {
    return entries;
  }

  public List<Entry> forFixture(String program, String fixture) {
    return entries.stream()
        .filter(entry -> entry.program().equals(program) && entry.fixture().equals(fixture))
        .toList();
  }

  public List<String> validate() throws IOException {
    List<String> errors = new ArrayList<>();
    String register = Files.readString(root.resolve("docs/modernization/quirk-register.md"));
    Set<String> seen = new HashSet<>();
    for (Entry entry : entries) {
      String key = entry.program() + "/" + entry.fixture() + "/" + entry.field();
      if (!seen.add(key)) {
        errors.add("duplicate known difference: " + key);
      }
      if (!QUIRK_ID.matcher(entry.quirk()).matches()) {
        errors.add("invalid quirk id for " + key + ": " + entry.quirk());
      } else if (!hasFixDecision(register, entry.quirk())) {
        errors.add("quirk must exist with a non-Preserve decision: " + entry.quirk());
      }
      if (entry.reason().isBlank()) {
        errors.add("known difference reason is blank: " + key);
      }
      if (!Files.isRegularFile(
          root.resolve("parity/fixtures")
              .resolve(entry.program())
              .resolve(entry.fixture() + ".json"))) {
        errors.add("fixture does not exist for known difference: " + key);
      }
    }
    return List.copyOf(errors);
  }

  public static boolean covers(String field, String path) {
    return path.equals(field) || path.startsWith(field + ".") || path.startsWith(field + "[");
  }

  private boolean hasFixDecision(String register, String quirk) {
    Matcher headings = QUIRK_HEADING.matcher(register);
    while (headings.find()) {
      if (!headings.group(1).equals(quirk)) {
        continue;
      }
      int start = headings.end();
      int next = register.indexOf("\n### ", start);
      String section = register.substring(start, next < 0 ? register.length() : next);
      Matcher decision =
          Pattern.compile("(?m)^- \\*\\*Decision:\\*\\* \\*\\*(.*?)\\*\\*").matcher(section);
      return decision.find() && !decision.group(1).startsWith("Preserve");
    }
    return false;
  }

  private static String stringValue(Map<String, Object> value, String name) {
    Object entry = value.get(name);
    return entry == null ? "" : entry.toString();
  }

  public record Entry(
      String program,
      String fixture,
      String field,
      String quirk,
      String reason,
      Object javaValue) {}
}
