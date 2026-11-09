package com.acme.insurance.pas.underwriting.messaging;

import com.acme.insurance.pas.underwriting.copybook.PolicyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default publisher until an MQ/JMS binding is chosen; logs the POLICY-RECORD payload. */
public class LoggingNotificationPublisher implements UnderwritingNotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationPublisher.class);

    @Override
    public void publish(String queue, PolicyRecord policyRecord) {
        log.info("WRITEQ {} policy={} uwStatus={} riskScore={}", queue, policyRecord.policyNumber(),
                policyRecord.uwStatus(), policyRecord.riskScore());
    }
}
