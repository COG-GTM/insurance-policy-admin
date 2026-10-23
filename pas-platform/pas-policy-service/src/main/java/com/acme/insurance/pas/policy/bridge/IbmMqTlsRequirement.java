package com.acme.insurance.pas.policy.bridge;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class IbmMqTlsRequirement implements InitializingBean {
  private final Environment environment;
  private final RouteFlags routeFlags;
  private final String cipherSuite;

  public IbmMqTlsRequirement(
      Environment environment,
      RouteFlags routeFlags,
      @Value("${pas.bridge.mq.tls-cipher-suite:}") String cipherSuite) {
    this.environment = environment;
    this.routeFlags = routeFlags;
    this.cipherSuite = cipherSuite;
  }

  @Override
  public void afterPropertiesSet() {
    boolean localOrTestProfile =
        Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("local") || profile.equals("test"));
    if (!localOrTestProfile
        && routeFlags.state(RouteFlags.DOCUMENT_EVENTS) == RouteState.NEW
        && cipherSuite.isBlank()) {
      throw new IllegalStateException(
          "pas.bridge.mq.tls-cipher-suite must be configured when document-events is NEW outside local/test profiles");
    }
  }
}
