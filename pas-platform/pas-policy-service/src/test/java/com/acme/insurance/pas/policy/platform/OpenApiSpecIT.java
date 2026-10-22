package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import java.nio.file.Files;
import java.nio.file.Path;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class OpenApiSpecIT {
  private static final Path SPEC_FILE = Path.of("src/main/resources/openapi/pas-policy-api.yaml");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;
  @LocalServerPort private int port;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void authenticatedYamlMatchesTheCommittedOpenApiContract() throws Exception {
    ResponseEntity<String> anonymous =
        restTemplate.getForEntity("http://localhost:" + port + "/v3/api-docs.yaml", String.class);
    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(
        PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, java.util.Map.of("book", "ALL")));
    ResponseEntity<String> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/v3/api-docs.yaml",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    String actual = normalize(response.getBody());
    if (Boolean.getBoolean("pas.openapi.write")) {
      Files.createDirectories(SPEC_FILE.getParent());
      Files.writeString(SPEC_FILE, actual);
    } else {
      assertThat(actual).isEqualTo(normalize(Files.readString(SPEC_FILE)));
    }
  }

  private static String normalize(String value) {
    return value.replace("\r\n", "\n").stripTrailing() + "\n";
  }
}
