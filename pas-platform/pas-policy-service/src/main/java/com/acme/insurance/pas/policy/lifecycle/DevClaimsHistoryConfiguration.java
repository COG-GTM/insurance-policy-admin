package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.domain.claims.ClaimsSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Local-profile claims stub so renewals can be exercised without the Claims system. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "pas.dev.claims-stub.enabled", havingValue = "true")
public class DevClaimsHistoryConfiguration {
  @Bean
  ClaimsHistoryPort devClaimsHistoryPort(
      @Value("${pas.dev.claims-stub.open-claims:0}") int openClaims) {
    return new ClaimsHistoryPort() {
      @Override
      public int countOpenClaims(String policyNumber) {
        return openClaims;
      }

      @Override
      public ClaimsSummary summarizeSince(String policyNumber, LocalDate since) {
        return new ClaimsSummary(openClaims, BigDecimal.ZERO);
      }
    };
  }
}
