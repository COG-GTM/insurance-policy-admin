package com.acme.insurance.pas.pii.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Scrubs message text for pattern layouts. Register with {@code <conversionRule
 * conversionWord="maskedMsg"
 * converterClass="com.acme.insurance.pas.pii.logging.PiiMaskingConverter"/>} and use {@code
 * %maskedMsg} instead of {@code %msg}.
 */
public final class PiiMaskingConverter extends MessageConverter {
  @Override
  public String convert(ILoggingEvent event) {
    return PiiLogScrubber.scrub(super.convert(event));
  }
}
