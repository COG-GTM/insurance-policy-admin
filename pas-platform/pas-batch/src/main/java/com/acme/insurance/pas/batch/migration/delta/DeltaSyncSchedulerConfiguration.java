package com.acme.insurance.pas.batch.migration.delta;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "pas.migration",
    name = {"enabled", "delta-sync.scheduler-enabled"},
    havingValue = "true")
public class DeltaSyncSchedulerConfiguration {}
