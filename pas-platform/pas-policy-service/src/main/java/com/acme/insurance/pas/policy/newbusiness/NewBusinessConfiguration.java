package com.acme.insurance.pas.policy.newbusiness;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DefaultCoverageProperties.class)
public class NewBusinessConfiguration {
  @Bean
  Clock policyClock(@Value("${pas.clock.zone:UTC}") String zone) {
    return Clock.system(ZoneId.of(zone));
  }
}
