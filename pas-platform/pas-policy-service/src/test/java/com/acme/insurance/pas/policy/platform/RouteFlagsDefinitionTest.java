package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RouteFlagsDefinitionTest {
  @Test
  void routeFileListsEveryKnownCapabilityAndSupportedState() throws Exception {
    JsonNode root =
        new ObjectMapper().readTree(Files.readString(Path.of("../routing/route-flags.json")));
    JsonNode capabilities = root.path("capabilities");
    Set<String> names = new java.util.HashSet<>();
    capabilities.fieldNames().forEachRemaining(names::add);
    Set<String> routes =
        java.util.stream.StreamSupport.stream(capabilities.spliterator(), false)
            .map(node -> node.path("route").asText())
            .collect(Collectors.toSet());

    assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(names).isEqualTo(RouteFlags.CAPABILITIES);
    assertThat(routes).allMatch(Set.of("legacy", "new", "retired")::contains);
  }
}
