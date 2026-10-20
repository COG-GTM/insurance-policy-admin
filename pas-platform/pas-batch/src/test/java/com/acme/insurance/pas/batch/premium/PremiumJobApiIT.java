package com.acme.insurance.pas.batch.premium;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/** Operator API: scope {@code batch:operate} required; async launch, poll, conflict. */
@AutoConfigureMockMvc
class PremiumJobApiIT extends PremiumJobTestSupport {

  @Autowired MockMvc mvc;

  /** CVE-2026-22732 triage (trivyignore): default security headers on every API response. */
  private static ResultMatcher securityHeaders() {
    return ResultMatcher.matchAll(
        header().string("X-Content-Type-Options", "nosniff"),
        header().string("X-Frame-Options", "DENY"),
        header().string("Cache-Control", Matchers.containsString("no-store")));
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor operator() {
    return jwt().authorities(new SimpleGrantedAuthority("SCOPE_batch:operate"));
  }

  @Test
  void launchRequiresBatchOperateScope() throws Exception {
    mvc.perform(post("/api/batch/premium/runs"))
        .andExpect(status().isUnauthorized())
        .andExpect(securityHeaders());
    mvc.perform(
            post("/api/batch/premium/runs")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_policy:read"))))
        .andExpect(status().isForbidden())
        .andExpect(securityHeaders());
    mvc.perform(get("/api/batch/premium/runs/1"))
        .andExpect(status().isUnauthorized())
        .andExpect(securityHeaders());
  }

  @Test
  void operatorLaunchesPollsAndCannotDoubleRunADate() throws Exception {
    resetBook();
    insertPolicy("API000000001", "AUT", "AC", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    String body =
        mvc.perform(post("/api/batch/premium/runs?runDate=2026-10-24").with(operator()))
            .andExpect(status().isAccepted())
            .andExpect(securityHeaders())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = ((Number) JsonPath.read(body, "$.jobExecutionId")).longValue();

    String state = "STARTING";
    for (int i = 0; i < 100 && !state.equals("COMPLETED") && !state.equals("FAILED"); i++) {
      Thread.sleep(200);
      state =
          JsonPath.read(
              mvc.perform(get("/api/batch/premium/runs/" + id).with(operator()))
                  .andReturn()
                  .getResponse()
                  .getContentAsString(),
              "$.batchStatus");
    }
    mvc.perform(get("/api/batch/premium/runs/" + id).with(operator()))
        .andExpect(status().isOk())
        .andExpect(securityHeaders())
        .andExpect(jsonPath("$.batchStatus").value("COMPLETED"))
        .andExpect(jsonPath("$.outcome").value("COMPLETED"))
        .andExpect(jsonPath("$.totals.read").value(1))
        .andExpect(jsonPath("$.totals.updated").value(1))
        .andExpect(jsonPath("$.totals.errors").value(0));

    mvc.perform(post("/api/batch/premium/runs?runDate=2026-10-24").with(operator()))
        .andExpect(status().isConflict())
        .andExpect(securityHeaders())
        .andExpect(jsonPath("$.code").value("PREMIUM_RUN_CONFLICT"));
    mvc.perform(post("/api/batch/premium/runs/" + id + "/restart").with(operator()))
        .andExpect(status().isConflict())
        .andExpect(securityHeaders());
    mvc.perform(post("/api/batch/premium/runs?runDate=2026-10-24&rerun=true").with(operator()))
        .andExpect(status().isAccepted())
        .andExpect(securityHeaders());
    mvc.perform(get("/api/batch/premium/runs/999999").with(operator()))
        .andExpect(status().isNotFound())
        .andExpect(securityHeaders());
  }

  @Test
  void securityHeadersAreWrittenOnEveryResponse() throws Exception {
    var requests =
        java.util.List.of(
            get("/api/batch/premium/runs/1"),
            get("/api/batch/premium/runs/1")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_policy:read"))),
            get("/api/batch/premium/runs/999999").with(operator()));
    int[] expected = {401, 403, 404};
    for (int i = 0; i < expected.length; i++) {
      mvc.perform(requests.get(i)).andExpect(status().is(expected[i])).andExpect(securityHeaders());
    }
  }
}
