package com.acme.insurance.pas.batch.platform;

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
@Import(BatchSecurityConfig.class)
@ActiveProfiles("test")
class WhoamiControllerTest {
  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void rejectsRequestsWithoutTokenWithBearerChallenge() throws Exception {
    mockMvc
        .perform(get("/api/batch/platform/whoami"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
  }

  @Test
  void returnsSubjectAndScopesForAuthenticatedJwt() throws Exception {
    mockMvc
        .perform(
            get("/api/batch/platform/whoami")
                .with(
                    SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(
                            jwt ->
                                jwt.subject("batch-agent")
                                    .claim("scope", PasScopes.BATCH_OPERATE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("batch-agent"))
        .andExpect(jsonPath("$.scopes[0]").value(PasScopes.BATCH_OPERATE));
  }

  @Test
  void tokenlessPostIsUnauthorizedRatherThanForbidden() throws Exception {
    mockMvc.perform(post("/api/batch/platform/whoami")).andExpect(status().isUnauthorized());
  }
}
