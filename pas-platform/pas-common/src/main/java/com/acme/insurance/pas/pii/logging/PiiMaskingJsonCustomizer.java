package com.acme.insurance.pas.pii.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

public final class PiiMaskingJsonCustomizer
    implements StructuredLoggingJsonMembersCustomizer<Object> {
  @Override
  public void customize(JsonWriter.Members<Object> members) {
    members.applyingValueProcessor(
        JsonWriter.ValueProcessor.of(String.class, PiiLogScrubber::scrub));
  }
}
