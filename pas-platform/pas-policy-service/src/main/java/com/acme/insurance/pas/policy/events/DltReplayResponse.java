package com.acme.insurance.pas.policy.events;

public record DltReplayResponse(
    int replayedCount, int skipped, long firstOffset, long lastOffset, String sourceTopic) {}
