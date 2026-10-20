package com.acme.insurance.pas.policy.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.insurance.pas.policy.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = PingController.class,
    properties = {"PAS_OIDC_ISSUER_URI=https://pas-test-issuer.local", "PAS_OIDC_AUDIENCE=pas"})
@Import(SecurityConfig.class)
class PingControllerTest {

  @Autowired private MockMvc mvc;
  @MockBean private JwtDecoder jwtDecoder;

  @Test
  void pingReturnsServiceStatusForAuthenticatedCaller() throws Exception {
    mvc.perform(get("/api/v2/ping").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.service").value("pas-policy-service"))
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void pingRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/v2/ping")).andExpect(status().isUnauthorized());
  }
}
