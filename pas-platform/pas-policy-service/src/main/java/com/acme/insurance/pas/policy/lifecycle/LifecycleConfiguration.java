package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.renewal.RenewalProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RenewalProperties.class)
public class LifecycleConfiguration {}
