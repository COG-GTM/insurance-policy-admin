package com.acme.insurance.pas.batch.routing;

import com.acme.insurance.pas.common.routing.RouteFlags;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BatchRouteProperties.class)
public class BatchRouteConfiguration {
  @Bean
  RouteFlags routeFlags(BatchRouteProperties properties) {
    return RouteFlags.of(properties.getRouting());
  }

  @Bean
  InfoContributor routeInfoContributor(RouteFlags routeFlags) {
    return (Info.Builder builder) -> builder.withDetail("routes", routeFlags.asMap());
  }
}
