package com.acme.insurance.pas.policy.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.insurance.pas.common.security.PasScopes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WhoamiController.class)
@Import(PolicySecurityConfig.class)
@ActiveProfiles("test")
class WhoamiControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void rejectsRequestsWithoutTokenWithBearerChallenge() throws Exception {
    mockMvc
        .perform(get("/api/v1/platform/whoami"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
  }

  @Test
  void returnsSubjectAndScopesForAuthenticatedJwt() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/platform/whoami")
                .with(
                    SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(
                            jwt ->
                                jwt.subject("agent-123")
                                    .claim(
                                        "scope",
                                        PasScopes.POLICY_READ + " " + PasScopes.POLICY_WRITE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("agent-123"))
        .andExpect(jsonPath("$.scopes[0]").value(PasScopes.POLICY_READ))
        .andExpect(jsonPath("$.scopes[1]").value(PasScopes.POLICY_WRITE));
  }

  @Test
  void tokenlessPostIsUnauthorizedRatherThanForbidden() throws Exception {
    mockMvc.perform(post("/api/v1/platform/whoami")).andExpect(status().isUnauthorized());
  }
}
