package com.acme.insurance.pas.policy.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Renewal / endorsement wiring (AB-526). */
@Configuration
@EnableConfigurationProperties(RenewalProperties.class)
public class LifecycleConfiguration {

  /** POLRNW flat cap (quirk register Q-04); replace this bean for state-specific caps. */
  @Bean
  @ConditionalOnMissingBean
  RenewalRateCapPolicy flatRenewalRateCap(RenewalProperties properties) {
    return policy -> properties.rateCapPct();
  }
}
