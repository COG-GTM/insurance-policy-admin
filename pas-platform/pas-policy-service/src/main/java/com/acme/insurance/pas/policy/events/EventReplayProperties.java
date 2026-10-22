package com.acme.insurance.pas.policy.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.events.replay")
public record EventReplayProperties(int maxRecords, Duration pollTimeout) {}
