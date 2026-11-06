package com.acme.insurance.pas.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the shared parity data under {@code parity/}: registry, fixtures, golden files, KDs. */
public final class ParityData {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path root;

  public ParityData(Path root) {
    this.root = root;
  }

  public static ParityData fromSystemProperty() {
    return new ParityData(Path.of(System.getProperty("parity.dir", "../../parity")).normalize());
  }

  public List<JsonNode> suites() {
    List<JsonNode> suites = new ArrayList<>();
    read(root.resolve("suites.json")).get("suites").forEach(suites::add);
    return suites;
  }

  public List<FixtureCase> fixtures(JsonNode suite) {
    String name = suite.get("suite").asText();
    List<FixtureCase> cases = new ArrayList<>();
    for (JsonNode node : read(root.resolve(suite.get("fixtures").asText())).get("cases")) {
      cases.add(
          new FixtureCase(
              name,
              node.get("id").asText(),
              node.get("description").asText(),
              LocalDate.parse(node.get("run_date").asText()),
              node.get("input"),
              strings(node.get("tags")),
              strings(node.get("quirks"))));
    }
    return cases;
  }

  public RunResult golden(String suite, String caseId) {
    JsonNode node = read(root.resolve("golden").resolve(suite).resolve(caseId + ".json"));
    JsonNode outcome = node.get("outcome");
    List<Effect> effects = new ArrayList<>();
    for (JsonNode effect : node.get("effects")) {
      effects.add(
          new Effect(
              effect.get("op").asText(),
              effect.has("sqlcode") ? effect.get("sqlcode").asInt() : null,
              map(effect.get("text")),
              map(effect.get("numbers")),
              map(effect.get("probes"))));
    }
    return new RunResult(
        outcome.get("status").asText(),
        outcome.get("return_code").asInt(),
        outcome.has("abend_code") ? outcome.get("abend_code").asText() : null,
        effects);
  }

  public List<KnownDifference> knownDifferences() {
    List<KnownDifference> result = new ArrayList<>();
    for (JsonNode node : read(root.resolve("known-differences.json")).get("differences")) {
      result.add(
          new KnownDifference(
              node.get("id").asText(),
              node.get("suite").asText(),
              strings(node.get("cases")),
              strings(node.get("paths")),
              node.get("quirk").asText(),
              node.get("decision").asText(),
              node.get("ticket").asText(),
              node.get("sign_off").asText(),
              node.get("reason").asText()));
    }
    return result;
  }

  private static JsonNode read(Path path) {
    try {
      return JSON.readTree(Files.readString(path));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }

  private static List<String> strings(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node != null) {
      node.forEach(value -> values.add(value.asText()));
    }
    return values;
  }

  private static Map<String, String> map(JsonNode node) {
    Map<String, String> values = new LinkedHashMap<>();
    if (node != null) {
      node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asText()));
    }
    return values;
  }
}
