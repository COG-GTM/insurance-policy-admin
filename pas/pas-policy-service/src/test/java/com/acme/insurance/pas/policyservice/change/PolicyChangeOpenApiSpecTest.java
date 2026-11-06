package com.acme.insurance.pas.policyservice.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * The published renewal/endorsement spec matches the controller and the request/response records.
 */
class PolicyChangeOpenApiSpecTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> spec() throws Exception {
    try (InputStream in =
        PolicyChangeOpenApiSpecTest.class.getResourceAsStream("/openapi/policy-change-v1.yaml")) {
      return new Yaml().load(in);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void pathsMatchTheControllerAndArePostOnly() throws Exception {
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec().get("paths");
    String base = PolicyChangeController.class.getAnnotation(RequestMapping.class).value()[0];
    Set<String> mapped = new HashSet<>();
    Arrays.stream(PolicyChangeController.class.getDeclaredMethods())
        .map(m -> m.getAnnotation(PostMapping.class))
        .filter(a -> a != null)
        .forEach(a -> mapped.add(base + a.value()[0]));
    assertThat(paths.keySet()).isEqualTo(mapped);
    paths.values().forEach(ops -> assertThat(ops.keySet()).containsOnly("post"));
    assertThat(
            ((Map<String, Object>) paths.get(base + "/{policyNumber}/renewals").get("post"))
                .get("security")
                .toString())
        .isEqualTo("[{oauth2=[pas.policy.renew]}]");
    assertThat(
            ((Map<String, Object>) paths.get(base + "/{policyNumber}/endorsements").get("post"))
                .get("security")
                .toString())
        .isEqualTo("[{oauth2=[pas.policy.endorse]}]");
  }

  @Test
  @SuppressWarnings("unchecked")
  void schemasMatchTheRecords() throws Exception {
    Map<String, Map<String, Object>> schemas =
        (Map<String, Map<String, Object>>)
            ((Map<String, Object>) spec().get("components")).get("schemas");
    assertProperties(schemas.get("RenewalRequest"), RenewalRequest.class);
    assertProperties(schemas.get("EndorsementRequest"), EndorsementRequest.class);
    assertProperties(schemas.get("Renewal"), RenewalView.class);
    assertProperties(schemas.get("Endorsement"), EndorsementView.class);
  }

  @SuppressWarnings("unchecked")
  private static void assertProperties(Map<String, Object> schema, Class<?> record) {
    Set<String> fields =
        Arrays.stream(record.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toSet());
    assertThat(((Map<String, Object>) schema.get("properties")).keySet())
        .as(record.getSimpleName())
        .isEqualTo(fields);
  }
}
