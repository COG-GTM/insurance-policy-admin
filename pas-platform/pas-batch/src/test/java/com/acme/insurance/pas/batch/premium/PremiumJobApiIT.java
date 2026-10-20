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

/** Operator API: scope {@code batch:operate} required; async launch, poll, conflict. */
@AutoConfigureMockMvc
class PremiumJobApiIT extends PremiumJobTestSupport {

  @Autowired MockMvc mvc;

  private static org.springframework.test.web.servlet.request.RequestPostProcessor operator() {
    return jwt().authorities(new SimpleGrantedAuthority("SCOPE_batch:operate"));
  }

  @Test
  void launchRequiresBatchOperateScope() throws Exception {
    mvc.perform(post("/api/batch/premium/runs")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/batch/premium/runs")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_policy:read"))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/batch/premium/runs/1")).andExpect(status().isUnauthorized());
  }

  @Test
  void operatorLaunchesPollsAndCannotDoubleRunADate() throws Exception {
    resetBook();
    insertPolicy("API000000001", "AUT", "AC", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    String body =
        mvc.perform(post("/api/batch/premium/runs?runDate=2026-10-24").with(operator()))
            .andExpect(status().isAccepted())
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
        .andExpect(jsonPath("$.batchStatus").value("COMPLETED"))
        .andExpect(jsonPath("$.outcome").value("COMPLETED"))
        .andExpect(jsonPath("$.totals.read").value(1))
        .andExpect(jsonPath("$.totals.updated").value(1))
        .andExpect(jsonPath("$.totals.errors").value(0));

    mvc.perform(post("/api/batch/premium/runs?runDate=2026-10-24").with(operator()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PREMIUM_RUN_CONFLICT"));
    mvc.perform(post("/api/batch/premium/runs/" + id + "/restart").with(operator()))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/batch/premium/runs?runDate=2026-10-24&rerun=true").with(operator()))
        .andExpect(status().isAccepted());
    mvc.perform(get("/api/batch/premium/runs/999999").with(operator()))
        .andExpect(status().isNotFound());
  }

  /** CVE-2026-22732 triage (trivyignore): default security headers are written on every outcome. */
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
      mvc.perform(requests.get(i))
          .andExpect(status().is(expected[i]))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(header().string("X-Frame-Options", "DENY"))
          .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
    }
  }
}
