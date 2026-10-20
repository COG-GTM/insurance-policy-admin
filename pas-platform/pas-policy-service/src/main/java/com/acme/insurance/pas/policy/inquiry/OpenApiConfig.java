package com.acme.insurance.pas.policy.inquiry;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata; the committed spec lives at {@code openapi/pas-policy-v1.yaml}. */
@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "PAS Policy Service",
            version = "v1",
            description = "Policy inquiry API replacing CICS PQRY and the legacy REST facade."),
    servers = @Server(url = "/", description = "Relative to the gateway route"))
@SecurityScheme(
    name = "bearer-jwt",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description =
        "OIDC access token. Scopes: policy:read, policy:pii:read. Entitlement claims: agent_code,"
            + " branch_code, book.")
public class OpenApiConfig {}
