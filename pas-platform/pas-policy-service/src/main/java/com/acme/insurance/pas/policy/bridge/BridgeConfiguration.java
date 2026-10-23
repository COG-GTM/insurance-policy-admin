package com.acme.insurance.pas.policy.bridge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BridgeProperties.class)
public class BridgeConfiguration {}
