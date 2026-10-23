package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import com.acme.insurance.pas.policy.dev.FacadeSeedLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pas.routing.broker-portal-read=new", "pas.routing.policy-inquiry=new"})
@ActiveProfiles("it")
class BrokerPortalApiOnlyIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private FacadeSeedLoader seedLoader;
  @Autowired private ObjectMapper mapper;
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
  void searchesOnlyEntitledPoliciesWithFiltersAndOffsetPaging() throws Exception {
    assertThat(get("", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("", PolicyJwtTestSupport.mint("other")).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    String agentJwt =
        PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("agent_id", "AG1001"));
    ResponseEntity<String> response = get("?agentCode=ag1001&page=0&size=1", agentJwt);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode page = mapper.readTree(response.getBody());
    assertThat(page.path("items").size()).isEqualTo(1);
    assertThat(page.path("items").get(0).path("agentCode").asText()).isEqualTo("AG1001");
    assertThat(page.path("page").asInt()).isZero();
    assertThat(page.path("size").asInt()).isEqualTo(1);
    assertThat(page.path("totalElements").asLong()).isPositive();
    assertThat(page.path("totalPages").asLong()).isPositive();

    ResponseEntity<String> filtered =
        get(
            "?branchCode=CHI1&status=ac&policyType=aut",
            PolicyJwtTestSupport.mint(
                PasScopes.POLICY_READ, Map.of("branch_codes", List.of("CHI1"))));
    assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(mapper.readTree(filtered.getBody()).path("items").size()).isPositive();
    assertThat(
            get(
                    "?size=201",
                    PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("book", "ALL")))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            get("", PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("agent_id", "AG9999")))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            get(
                    "/POL-00000001",
                    PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("agent_id", "AG9999")))
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            get(
                    "/POL-00000001",
                    PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("agent_id", "AG1001")))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<String> get(String suffix, String jwt) {
    HttpHeaders headers = new HttpHeaders();
    if (jwt != null) {
      headers.setBearerAuth(jwt);
    }
    return restTemplate.exchange(
        "http://localhost:" + port + "/api/v1/policies" + suffix,
        HttpMethod.GET,
        new HttpEntity<>(headers),
        String.class);
  }
}
