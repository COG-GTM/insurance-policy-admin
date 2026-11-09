package com.acme.insurance.pas.underwriting.messaging;

import com.acme.insurance.pas.underwriting.copybook.PolicyRecord;

/**
 * 7000-SEND-MQ-NOTIFICATION: EXEC CICS WRITEQ TD QUEUE(WS-MQ-QUEUE) FROM(POLICY-RECORD).
 * Implementations may throw; the caller ignores failures exactly as UNDWRT ignores RESP.
 */
public interface UnderwritingNotificationPublisher {

    void publish(String queue, PolicyRecord policyRecord);
}
