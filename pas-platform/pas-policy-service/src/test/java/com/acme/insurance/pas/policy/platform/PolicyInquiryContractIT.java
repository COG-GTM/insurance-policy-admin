package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import com.acme.insurance.pas.policy.dev.FacadeSeedLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class PolicyInquiryContractIT {
  private static final String POLICY_ORDER =
      "policyNumber,policyType,policyStatus,effectiveDate,expiryDate,policyholderId,agentCode,"
          + "branchCode,totalPremium,deductible,coverageLimit,inceptionDate,renewalCount,uwStatus,"
          + "riskScore,webIndicator,apiFlag,lastUpdated,updatedBy";
  private static final String COVERAGE_ORDER =
      "policyNumber,sequenceNum,coverageType,description,coverageLimit,deductible,premium,"
          + "effectiveDate,expiryDate,status,coinsurancePct,ratingTerritory,classCode";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private FacadeSeedLoader seedLoader;
  @Autowired private ObjectMapper objectMapper;
  @LocalServerPort private int port;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @BeforeEach
  void seedFacadeData() {
    seedLoader.load();
  }

  @Test
  void matchesEverySnapshotResponseIncludingTypesOrderingAndEmptyNotFoundBodies() throws Exception {
    JsonNode snapshots =
        objectMapper.readTree(
            Files.readString(Path.of("../../compat/api/snapshots/policies-v1.json")));
    String jwt = PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("book", "ALL"));

    Iterator<Map.Entry<String, JsonNode>> entries = snapshots.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> entry = entries.next();
      ResponseEntity<String> response = get(entry.getKey(), jwt);
      assertThat(response.getStatusCode().value())
          .as("status for %s", entry.getKey())
          .isEqualTo(entry.getValue().path("status").asInt());
      JsonNode expected = entry.getValue().path("body");
      if (expected.isNull()) {
        assertThat(response.getBody()).as("empty body for %s", entry.getKey()).isNullOrEmpty();
        continue;
      }
      JsonNode actual = objectMapper.readTree(response.getBody());
      compareSnapshot(expected, actual, entry.getKey());
      if (entry.getKey().endsWith("/coverages")) {
        for (JsonNode coverage : actual) {
          assertThat(fieldOrder(coverage)).isEqualTo(COVERAGE_ORDER);
        }
      } else {
        assertThat(fieldOrder(actual)).isEqualTo(POLICY_ORDER);
      }
    }
  }

  private void compareSnapshot(JsonNode expected, JsonNode actual, String path) {
    if (expected.isObject()) {
      assertThat(actual.isObject()).as(path).isTrue();
      Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        assertThat(actual.has(field.getKey())).as(path + "." + field.getKey()).isTrue();
        compareSnapshot(field.getValue(), actual.get(field.getKey()), path + "." + field.getKey());
      }
      return;
    }
    if (expected.isArray()) {
      assertThat(actual.isArray()).as(path).isTrue();
      assertThat(actual.size()).as(path + " length").isEqualTo(expected.size());
      for (int index = 0; index < expected.size(); index++) {
        compareSnapshot(expected.get(index), actual.get(index), path + "[" + index + "]");
      }
      return;
    }
    if (path.endsWith(".lastUpdated")) {
      assertThat(expected.isNumber()).isEqualTo(actual.isNumber());
    } else if (expected.isNumber()) {
      assertThat(actual.isNumber()).as(path + " JSON number").isTrue();
      assertThat(expected.decimalValue()).as(path).isEqualByComparingTo(actual.decimalValue());
    } else {
      assertThat(actual).as(path).isEqualTo(expected);
    }
  }

  private static String fieldOrder(JsonNode object) {
    List<String> names = new ArrayList<>();
    object.fieldNames().forEachRemaining(names::add);
    return String.join(",", names);
  }

  private ResponseEntity<String> get(String path, String jwt) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt);
    return restTemplate.exchange(
        "http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
