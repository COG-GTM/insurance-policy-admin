package com.acme.insurance.pas.policy.bridge;

import com.acme.insurance.pas.common.routing.RouteState;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class DocumentEventsNewCondition implements Condition {
  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String configuredState =
        context.getEnvironment().getProperty("pas.routing.document-events", "legacy");
    return RouteState.parse(configuredState) == RouteState.NEW;
  }
}
