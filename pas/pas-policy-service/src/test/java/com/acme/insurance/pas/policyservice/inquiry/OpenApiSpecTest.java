package com.acme.insurance.pas.policyservice.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * The published OpenAPI spec matches the controller and the response records, and covers every
 * field of the facade's recorded contract.
 */
class OpenApiSpecTest {

  private static final String PREFIX = "/api/v1/policies";

  @SuppressWarnings("unchecked")
  private static Map<String, Object> spec() throws Exception {
    try (InputStream in =
        OpenApiSpecTest.class.getResourceAsStream("/openapi/policy-inquiry-v1.yaml")) {
      return new Yaml().load(in);
    }
  }

  @Test
  void pathsMatchTheControllerAndAreReadOnly() throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec().get("paths");
    String base = PolicyInquiryController.class.getAnnotation(RequestMapping.class).value()[0];
    Set<String> mapped = new HashSet<>();
    Arrays.stream(PolicyInquiryController.class.getDeclaredMethods())
        .map(m -> m.getAnnotation(GetMapping.class))
        .filter(a -> a != null)
        .forEach(a -> mapped.add(base + a.value()[0]));
    assertThat(base).isEqualTo(PREFIX);
    assertThat(paths.keySet()).isEqualTo(mapped);
    paths.values().forEach(ops -> assertThat(ops.keySet()).containsOnly("get"));
  }

  @Test
  void schemasMatchTheResponseRecords() throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> schemas =
        (Map<String, Map<String, Object>>)
            ((Map<String, Object>) spec().get("components")).get("schemas");
    assertProperties(schemas.get("Policy"), PolicyView.class);
    assertProperties(schemas.get("Coverage"), CoverageView.class);
    assertProperties(schemas.get("PolicyHolder"), PolicyHolderView.class);
  }

  @Test
  void everyEndpointRequiresTheReadScope() throws Exception {
    assertThat(spec().get("security").toString()).isEqualTo("[{oauth2=[pas.policy.read]}]");
  }

  @Test
  void specCoversEveryFieldOfTheFacadeSnapshot() throws Exception {
    Path snapshot = repoRoot().resolve("compat/api/snapshots/policies-v1.json");
    JsonNode snapshots = new ObjectMapper().readTree(snapshot.toFile());
    Set<String> policyFields = fields(PolicyView.class);
    Set<String> coverageFields = fields(CoverageView.class);
    for (Iterator<Map.Entry<String, JsonNode>> it = snapshots.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = it.next();
      JsonNode body = entry.getValue().get("body");
      if (body == null || body.isNull()) {
        continue;
      }
      List<JsonNode> objects = body.isArray() ? iterate(body) : List.of(body);
      Set<String> expected = entry.getKey().endsWith("/coverages") ? coverageFields : policyFields;
      for (JsonNode object : objects) {
        object
            .fieldNames()
            .forEachRemaining(f -> assertThat(expected).as(entry.getKey()).contains(f));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void assertProperties(Map<String, Object> schema, Class<?> record) {
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties.keySet()).as(record.getSimpleName()).isEqualTo(fields(record));
  }

  private static Set<String> fields(Class<?> record) {
    Set<String> names = new HashSet<>();
    for (RecordComponent c : record.getRecordComponents()) {
      names.add(c.getName());
    }
    return names;
  }

  private static List<JsonNode> iterate(JsonNode array) {
    List<JsonNode> out = new java.util.ArrayList<>();
    array.forEach(out::add);
    return out;
  }

  private static Path repoRoot() {
    Path dir = new File("").getAbsoluteFile().toPath();
    while (dir != null && !Files.exists(dir.resolve("compat/api/snapshots/policies-v1.json"))) {
      dir = dir.getParent();
    }
    assertThat(dir).isNotNull();
    return dir;
  }
}
