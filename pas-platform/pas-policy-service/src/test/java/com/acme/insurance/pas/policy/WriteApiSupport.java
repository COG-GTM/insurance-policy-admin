package com.acme.insurance.pas.policy;

import com.acme.insurance.pas.policy.persistence.PolicyHolderEntity;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared seeding and HTTP helpers for the AB-525 write-API integration tests. */
public final class WriteApiSupport {

  public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/New_York");

  private WriteApiSupport() {}

  public static LocalDate today() {
    return LocalDate.now(BUSINESS_ZONE);
  }

  public static void holder(
      PolicyHolderRepository holders, String custId, Integer creditScore, String riskTier) {
    PolicyHolderEntity h = new PolicyHolderEntity();
    h.setCustId(custId);
    h.setCustType("I");
    h.setLastName("Tester");
    h.setFirstName("Pat");
    h.setCreditScore(creditScore);
    h.setRiskTier(riskTier);
    h.setGdprConsent("N");
    h.setCreatedDate(LocalDate.of(2020, 1, 1));
    h.setLastUpdated(LocalDateTime.of(2020, 1, 1, 0, 0));
    holders.saveAndFlush(h);
  }

  public static void policy(
      JdbcTemplate jdbc,
      String number,
      String holderId,
      String agent,
      String branch,
      String status,
      String premium,
      String limit) {
    jdbc.update(
        "INSERT INTO pas.policies (policy_number, policy_type, policy_status, effective_date,"
            + " expiry_date, policyholder_id, agent_code, branch_code, total_premium,"
            + " coverage_limit) VALUES (?, 'AUT', ?, ?, ?, ?, ?, ?, ?, ?)",
        number,
        status,
        LocalDate.of(2025, 1, 1),
        LocalDate.of(2026, 1, 1),
        holderId,
        agent,
        branch,
        new BigDecimal(premium),
        new BigDecimal(limit));
  }

  public static String createBody(
      String holderId, String type, LocalDate effective, String limit, String agent) {
    return """
        {"policyholderId":"%s","policyType":"%s","effectiveDate":"%s","expiryDate":"%s",\
        "coverageLimit":%s,"totalPremium":1200.00,"deductible":500.00,\
        "agentCode":"%s","branchCode":"B001"}"""
        .formatted(holderId, type, effective, effective.plusYears(1), limit, agent);
  }

  public static ResponseEntity<String> send(
      TestRestTemplate rest,
      HttpMethod method,
      String path,
      String bearer,
      String body,
      Map<String, String> extraHeaders) {
    HttpHeaders headers = new HttpHeaders();
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    extraHeaders.forEach(headers::add);
    return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }
}
