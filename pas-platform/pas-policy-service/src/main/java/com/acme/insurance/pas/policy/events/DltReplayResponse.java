package com.acme.insurance.pas.policy.events;

public record DltReplayResponse(
    int replayedCount, long firstOffset, long lastOffset, String sourceTopic) {}
