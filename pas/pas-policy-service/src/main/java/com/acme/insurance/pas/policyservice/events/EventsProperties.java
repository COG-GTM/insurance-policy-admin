package com.acme.insurance.pas.policyservice.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pas.events.*}: the outbox relay. Off by default, so a service without a broker keeps its
 * events safely in the outbox. {@code create-topics} is for local stacks and tests; shared
 * environments get the topics from the platform (MSG-01).
 *
 * @param relayEnabled publish outbox rows to Kafka
 * @param createTopics create the pas.* topics at start-up
 * @param batchSize rows per relay run
 * @param sendTimeout how long one publish may take before the row is retried
 * @param retention how long published rows stay in the outbox (DS-04: 7 days)
 * @param partitions partitions of topics created at start-up
 */
@ConfigurationProperties("pas.events")
public record EventsProperties(
    @DefaultValue("false") boolean relayEnabled,
    @DefaultValue("false") boolean createTopics,
    @DefaultValue("100") int batchSize,
    @DefaultValue("PT10S") Duration sendTimeout,
    @DefaultValue("P7D") Duration retention,
    @DefaultValue("3") int partitions,
    @DefaultValue("10") int maxAttempts) {}
