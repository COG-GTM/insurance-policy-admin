package com.acme.insurance.pas.policy.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalLegacyQueueConfiguration {
  private static final Logger log = LoggerFactory.getLogger(LocalLegacyQueueConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(LegacyQueuePort.class)
  LegacyQueuePort localLegacyQueuePort() {
    return (queueName, body, properties) ->
        log.info("Simulated local legacy queue delivery queue={} bytes={}", queueName, body.length);
  }
}
