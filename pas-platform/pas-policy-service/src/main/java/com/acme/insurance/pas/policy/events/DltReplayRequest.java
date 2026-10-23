package com.acme.insurance.pas.policy.events;

public record DltReplayRequest(
    String dltTopic, int partition, long fromOffset, long toOffset, Integer maxRecords) {}
