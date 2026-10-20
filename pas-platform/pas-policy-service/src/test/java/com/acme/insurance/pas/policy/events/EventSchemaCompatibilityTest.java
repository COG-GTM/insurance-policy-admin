package com.acme.insurance.pas.policy.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fails on a breaking change to a published event schema. {@code src/test/resources/events/
 * schema-baseline/v1} is the frozen copy of every v1 schema; the committed schema under {@code
 * src/main/resources/events/schemas/v1} may only add optional properties. A breaking change needs a
 * new {@code v2} schema and topic (ADR 0001 D4), not an edit of v1.
 */
class EventSchemaCompatibilityTest {

  static final Path CURRENT = Path.of("src/main/resources/events/schemas/v1");
  static final Path BASELINE = Path.of("src/test/resources/events/schema-baseline/v1");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void everyPublishedSchemaStaysBackwardCompatible() {
    Set<String> baseline = names(BASELINE);
    assertThat(baseline).isNotEmpty();
    for (String name : baseline) {
      Path current = CURRENT.resolve(name);
      assertThat(current).as("published schema %s was removed", name).exists();
      assertThat(breakingChanges(read(BASELINE.resolve(name)), read(current)))
          .as("breaking changes in %s", name)
          .isEmpty();
    }
  }

  @Test
  void everyEventTypeHasASchemaWithAMatchingConstAndVersion() throws Exception {
    Set<String> types = new TreeSet<>();
    for (Field f : PasEventTypes.class.getFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
        types.add((String) f.get(null));
      }
    }
    Set<String> schemas = new TreeSet<>();
    for (String name : names(CURRENT)) {
      JsonNode s = read(CURRENT.resolve(name));
      String type = s.at("/properties/eventType/const").asText();
      assertThat(name).isEqualTo(type + ".schema.json");
      assertThat(s.at("/properties/schemaVersion/const").asInt())
          .isEqualTo(PasEventTypes.SCHEMA_VERSION);
      schemas.add(type);
    }
    assertThat(schemas).isEqualTo(types);
    assertThat(names(BASELINE)).isEqualTo(names(CURRENT));
  }

  @Test
  void detectsBreakingChanges() {
    JsonNode base = read(BASELINE.resolve("PolicyRenewed.schema.json"));

    ObjectNode removed = base.deepCopy();
    ((ObjectNode) removed.at("/properties/data/properties")).remove("renewalCount");
    assertThat(breakingChanges(base, removed)).anyMatch(m -> m.contains("removed"));

    ObjectNode retyped = base.deepCopy();
    ((ObjectNode) retyped.at("/properties/data/properties/renewalCount")).put("type", "string");
    assertThat(breakingChanges(base, retyped)).anyMatch(m -> m.contains("type"));

    ObjectNode newlyRequired = base.deepCopy();
    ((ObjectNode) newlyRequired.at("/properties/data/properties"))
        .putObject("brandNew")
        .put("type", "string");
    assertThat(breakingChanges(base, newlyRequired)).isEmpty();
    ((com.fasterxml.jackson.databind.node.ArrayNode) newlyRequired.at("/properties/data/required"))
        .add("brandNew");
    assertThat(breakingChanges(base, newlyRequired)).anyMatch(m -> m.contains("required"));
  }

  /** Differences that would break an existing consumer or replay of stored events. */
  static List<String> breakingChanges(JsonNode baseline, JsonNode current) {
    List<String> out = new ArrayList<>();
    compare("", baseline, current, out);
    return out;
  }

  private static void compare(String path, JsonNode base, JsonNode cur, List<String> out) {
    for (String key : List.of("type", "format", "const", "$ref")) {
      if (base.has(key) && !base.get(key).equals(cur.get(key))) {
        out.add(path + ": " + key + " changed from " + base.get(key) + " to " + cur.get(key));
      }
    }
    if (base.has("enum")) {
      Set<JsonNode> now = new HashSet<>();
      cur.path("enum").forEach(now::add);
      base.get("enum")
          .forEach(
              v -> {
                if (!now.contains(v)) {
                  out.add(path + ": enum value " + v + " removed");
                }
              });
    }
    Set<String> baseRequired = strings(base.path("required"));
    Set<String> curRequired = strings(cur.path("required"));
    for (String r : baseRequired) {
      if (!curRequired.contains(r)) {
        out.add(path + ": " + r + " no longer required");
      }
    }
    for (String r : curRequired) {
      if (!baseRequired.contains(r)) {
        out.add(path + ": " + r + " newly required");
      }
    }
    JsonNode baseProps = base.path("properties");
    baseProps
        .fieldNames()
        .forEachRemaining(
            name -> {
              JsonNode next = cur.path("properties").get(name);
              if (next == null) {
                out.add(path + "/" + name + ": property removed");
              } else {
                compare(path + "/" + name, baseProps.get(name), next, out);
              }
            });
    if (base.has("items")) {
      compare(path + "[]", base.get("items"), cur.path("items"), out);
    }
    if (base.path("additionalProperties").isBoolean()
        && !base.get("additionalProperties").equals(cur.get("additionalProperties"))) {
      out.add(path + ": additionalProperties changed");
    }
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> s = new TreeSet<>();
    array.forEach(n -> s.add(n.asText()));
    return s;
  }

  static Set<String> names(Path dir) {
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".schema.json"))
          .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static JsonNode read(Path p) {
    try {
      return JSON.readTree(p.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
