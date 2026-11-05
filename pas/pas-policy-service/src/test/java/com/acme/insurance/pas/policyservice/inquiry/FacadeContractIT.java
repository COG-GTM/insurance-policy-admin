package com.acme.insurance.pas.policyservice.inquiry;

import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_READ;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policyservice.TestJwt;
import com.acme.insurance.pas.policyservice.TestcontainersConfiguration;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * AB-524 acceptance: the new API passes the REST contract recorded from the Spring Boot 1.5 facade.
 * Runs the compat gate's own checker (compat/api/check_api.py against
 * compat/api/snapshots/policies-v1.json) against this service, loaded with the facade's seed data,
 * with a whole-book read token. Without a token the same check must fail on status codes: that is
 * the one deliberate contract change (authentication), recorded in docs/pas/policy-inquiry-api.md.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
@Sql(
    scripts = "classpath:contract/facade-seed.sql",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class FacadeContractIT {

  @LocalServerPort int port;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  @Test
  void passesTheFacadeContractWithAnEntitledToken() throws Exception {
    Result result = checkApi("Authorization: Bearer " + TestJwt.wholeBook(POLICY_READ));
    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(result.output()).contains("PASS");
  }

  @Test
  void failsTheFacadeContractWithoutAToken() throws Exception {
    Result result = checkApi(null);
    assertThat(result.exitCode()).as(result.output()).isNotZero();
    assertThat(result.output()).contains("401");
  }

  private Result checkApi(String authHeader) throws Exception {
    Path repoRoot = repoRoot();
    ProcessBuilder builder =
        new ProcessBuilder(
                "python3", "compat/api/check_api.py", "--base-url", "http://localhost:" + port)
            .directory(repoRoot.toFile())
            .redirectErrorStream(true);
    builder.environment().remove("COMPAT_AUTH_HEADER");
    if (authHeader != null) {
      builder.environment().put("COMPAT_AUTH_HEADER", authHeader);
    }
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
    return new Result(process.exitValue(), output);
  }

  private static Path repoRoot() {
    Path dir = new File("").getAbsoluteFile().toPath();
    while (dir != null && !Files.exists(dir.resolve("compat/api/check_api.py"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repository root with compat/api/check_api.py").isNotNull();
    return dir;
  }

  private record Result(int exitCode, String output) {}
}
