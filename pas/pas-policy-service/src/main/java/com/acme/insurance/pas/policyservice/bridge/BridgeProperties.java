package com.acme.insurance.pas.policyservice.bridge;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka-to-legacy bridge (ADR 0001 D4, component INT-01, AB-528). Off by default; the IBM MQ side
 * is the Kafka Connect MQ sink in {@code pas/deploy/messaging/}, which moves the bridge topics to
 * the existing queues byte for byte.
 *
 * @param documentTriggerTopic 125-byte POLICY-RECORD per new, renewed or endorsed policy for the
 *     FileNet document trigger (I-6)
 * @param underwritingRequestTopic POLICY-RECORD for ACME.PAS.UNDERWRITING.REQUEST (I-4)
 * @param underwritingDecisionTopic POLICY-RECORD for ACME.PAS.UNDERWRITING.DECISION (I-5)
 * @param deadLetterTopic where events the bridge cannot process go after the retries
 */
@ConfigurationProperties("pas.bridge")
public record BridgeProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("pas-legacy-bridge") String groupId,
    @DefaultValue("pas.bridge.filenet.document-trigger") String documentTriggerTopic,
    @DefaultValue("pas.bridge.mq.underwriting-request") String underwritingRequestTopic,
    @DefaultValue("pas.bridge.mq.underwriting-decision") String underwritingDecisionTopic,
    @DefaultValue("pas.events.dlt") String deadLetterTopic,
    @DefaultValue("3") int retries,
    @DefaultValue("PT1S") Duration retryInterval,
    @DefaultValue("PT10S") Duration sendTimeout) {}
