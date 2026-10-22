package com.acme.insurance.pas.policy.underwriting;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UnderwritingThresholds.class)
public class UnderwritingConfiguration {}
