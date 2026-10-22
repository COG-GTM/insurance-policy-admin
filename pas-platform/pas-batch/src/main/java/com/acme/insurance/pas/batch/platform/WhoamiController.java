package com.acme.insurance.pas.batch.platform;

import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/batch/platform")
public class WhoamiController {
  @GetMapping("/whoami")
  public WhoamiResponse whoami(@AuthenticationPrincipal Jwt jwt) {
    String scope = jwt.getClaimAsString("scope");
    List<String> scopes =
        scope == null || scope.isBlank() ? List.of() : Arrays.asList(scope.split("\\s+"));
    return new WhoamiResponse(jwt.getSubject(), scopes);
  }

  public record WhoamiResponse(String subject, List<String> scopes) {
    public WhoamiResponse {
      scopes = List.copyOf(scopes);
    }
  }
}
