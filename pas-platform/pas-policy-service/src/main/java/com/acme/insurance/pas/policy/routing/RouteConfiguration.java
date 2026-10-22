package com.acme.insurance.pas.policy.routing;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RouteProperties.class)
public class RouteConfiguration {
  @Bean
  RouteFlags routeFlags(RouteProperties properties) {
    RouteFlags routeFlags = RouteFlags.of(properties.getRouting());
    RouteState brokerPortalRead = routeFlags.state(RouteFlags.BROKER_PORTAL_READ);
    if ((brokerPortalRead == RouteState.NEW || brokerPortalRead == RouteState.SHADOW)
        && !routeFlags.servesTraffic(RouteFlags.POLICY_INQUIRY)) {
      throw new IllegalStateException(
          "Route flag broker-portal-read requires policy-inquiry to serve traffic");
    }
    return routeFlags;
  }

  @Bean
  InfoContributor routeInfoContributor(RouteFlags routeFlags) {
    return (Info.Builder builder) -> builder.withDetail("routes", routeFlags.asMap());
  }

  @Bean
  OpenApiCustomizer removeRequestSpecificServerUrls() {
    return openApi -> openApi.setServers(List.of());
  }

  @Bean
  OpenAPI policyApi() {
    return new OpenAPI()
        .components(
            new io.swagger.v3.oas.models.Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new io.swagger.v3.oas.models.security.SecurityScheme()
                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }
}
