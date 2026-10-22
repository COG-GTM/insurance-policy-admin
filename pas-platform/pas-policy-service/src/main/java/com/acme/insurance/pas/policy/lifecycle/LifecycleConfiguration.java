package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.renewal.RenewalProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RenewalProperties.class)
public class LifecycleConfiguration {
  @Bean
  @ConditionalOnMissingBean
  Clock pasClock() {
    return Clock.systemUTC();
  }
}
