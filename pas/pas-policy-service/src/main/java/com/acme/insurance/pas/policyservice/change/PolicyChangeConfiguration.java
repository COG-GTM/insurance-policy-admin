package com.acme.insurance.pas.policyservice.change;

import com.acme.insurance.pas.rating.RenewalCalculator;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Renewal and endorsement wiring (AB-526). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PolicyChangeProperties.class)
public class PolicyChangeConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyChangeConfiguration.class);

  @Bean
  RenewalCalculator renewalCalculator(PolicyChangeProperties properties) {
    RenewalCalculator calculator = new RenewalCalculator(properties.renewalRules());
    LOG.info("pas.policy-change renewal rules={}", calculator.rules());
    return calculator;
  }

  @Bean
  PolicyChangeClock policyChangeClock(PolicyChangeProperties properties) {
    return new PolicyChangeClock(Clock.system(properties.businessZone()));
  }

  @Bean
  OpenClaimsRule openClaimsRule(
      PolicyChangeProperties properties, ObjectProvider<OpenClaims> openClaims) {
    Integer max = properties.maxOpenClaims();
    if (max == null) {
      return OpenClaimsRule.NOT_ENFORCED;
    }
    OpenClaims source = openClaims.getIfAvailable();
    if (source == null) {
      throw new IllegalStateException(
          "pas.policy-change.max-open-claims is set but no Claims read interface (OpenClaims) is"
              + " configured (FU-17)");
    }
    return new OpenClaimsRule(max, source);
  }

  /** The clock renewals and endorsements read the business date and audit timestamps from. */
  public record PolicyChangeClock(Clock clock) {}

  /** Open-claim eligibility: not enforced by default, as in POLRNW (Q-02). */
  public record OpenClaimsRule(Integer maxOpenClaims, OpenClaims source) {

    static final OpenClaimsRule NOT_ENFORCED = new OpenClaimsRule(null, null);

    boolean blocks(String policyNumber) {
      return maxOpenClaims != null && source.openClaimCount(policyNumber) > maxOpenClaims;
    }
  }
}
