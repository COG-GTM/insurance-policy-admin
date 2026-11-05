package com.acme.insurance.pas.policyservice.bridge;

import java.util.Map;

/**
 * One message for a bridge topic: key = policy number, value = the 125-byte EBCDIC POLICY-RECORD,
 * headers copied to JMS properties by the MQ sink.
 */
public record BridgeMessage(String topic, String key, byte[] value, Map<String, String> headers) {}
