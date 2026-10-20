package com.acme.insurance.pas.policy.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.policy.security.PasScopes;
import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fails when the generated OpenAPI document differs from the committed {@code
 * openapi/pas-policy-v1.yaml}. Regenerate with {@code ./mvnw -pl pas-policy-service verify
 * -Dit.test=OpenApiDriftIT -Dopenapi.update=true}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenApiDriftIT {

  static final Path COMMITTED = Path.of("src/main/resources/openapi/pas-policy-v1.yaml");

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
    TestJwtIssuer.register(registry);
  }

  @Autowired private TestRestTemplate rest;

  @Test
  void committedSpecMatchesGenerated() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(TestJwtIssuer.bearer().scopes(PasScopes.POLICY_READ).sign());
    ResponseEntity<String> res =
        rest.exchange("/v3/api-docs.yaml", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    String generated = res.getBody();

    if (Boolean.getBoolean("openapi.update")) {
      Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
    }
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    assertThat(yaml.readTree(generated))
        .as("OpenAPI drift: regenerate %s with -Dopenapi.update=true", COMMITTED)
        .isEqualTo(yaml.readTree(Files.readString(COMMITTED)));
  }
}
