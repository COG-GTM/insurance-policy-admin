package com.acme.insurance.pas.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The released event schemas under {@code /schemas} (AB-528) and a validator for the JSON Schema
 * subset they use: type, const, required, properties, additionalProperties false, items, maxLength
 * and pattern. Consumers outside this repository use the same files with any 2020-12 validator.
 */
public final class EventSchemas {

  public static final String REGISTRY = "/schemas/registry.json";

  private static final ObjectMapper JSON = new ObjectMapper();

  private EventSchemas() {}

  /** The registry entries: subject (topic), version, file, sha256, compatibility. */
  public static JsonNode registry() {
    return read(REGISTRY);
  }

  public static JsonNode schema(PasEventType type, int version) {
    for (JsonNode entry : registry().get("schemas")) {
      if (entry.get("subject").asText().equals(type.topic())
          && entry.get("version").asInt() == version) {
        return read("/schemas/" + entry.get("file").asText());
      }
    }
    throw new IllegalArgumentException("no schema for " + type.topic() + " v" + version);
  }

  /** The violations of {@code json} against the schema of its type and version; empty if valid. */
  public static List<String> validate(PasEventType type, int version, String json) {
    try {
      return validate(schema(type, version), JSON.readTree(json));
    } catch (IOException e) {
      return List.of("not JSON: " + e.getClass().getSimpleName());
    }
  }

  public static List<String> validate(JsonNode schema, JsonNode instance) {
    List<String> errors = new ArrayList<>();
    check(schema, instance, "$", errors);
    return errors;
  }

  private static void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
    if (schema.has("const") && !schema.get("const").equals(value)) {
      errors.add(path + ": expected " + schema.get("const"));
      return;
    }
    if (schema.has("type") && !typeMatches(schema.get("type"), value)) {
      errors.add(path + ": type " + value.getNodeType() + " not allowed by " + schema.get("type"));
      return;
    }
    if (value.isTextual()) {
      if (schema.has("maxLength") && value.asText().length() > schema.get("maxLength").asInt()) {
        errors.add(path + ": longer than " + schema.get("maxLength").asInt());
      }
      if (schema.has("pattern")
          && !Pattern.compile(schema.get("pattern").asText()).matcher(value.asText()).find()) {
        errors.add(path + ": does not match " + schema.get("pattern").asText());
      }
    }
    if (value.isObject()) {
      for (JsonNode required : schema.path("required")) {
        if (!value.has(required.asText())) {
          errors.add(path + ": missing " + required.asText());
        }
      }
      JsonNode properties = schema.path("properties");
      for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        String child = path + "." + field.getKey();
        if (properties.has(field.getKey())) {
          check(properties.get(field.getKey()), field.getValue(), child, errors);
        } else if (schema.path("additionalProperties").isBoolean()
            && !schema.get("additionalProperties").asBoolean()) {
          errors.add(child + ": not allowed");
        }
      }
    }
    if (value.isArray() && schema.has("items")) {
      for (int i = 0; i < value.size(); i++) {
        check(schema.get("items"), value.get(i), path + "[" + i + "]", errors);
      }
    }
  }

  private static boolean typeMatches(JsonNode type, JsonNode value) {
    if (type.isArray()) {
      for (JsonNode t : type) {
        if (typeMatches(t, value)) {
          return true;
        }
      }
      return false;
    }
    return switch (type.asText()) {
      case "string" -> value.isTextual();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "null" -> value.isNull();
      default -> false;
    };
  }

  private static JsonNode read(String resource) {
    try (InputStream in = EventSchemas.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + resource);
      }
      return JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
