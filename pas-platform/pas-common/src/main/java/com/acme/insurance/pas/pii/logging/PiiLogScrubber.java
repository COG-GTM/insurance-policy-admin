package com.acme.insurance.pas.pii.logging;

import java.util.regex.Pattern;

public final class PiiLogScrubber {
  private static final Pattern SENSITIVE_VALUE =
      Pattern.compile(
          "(?i)([\"']?(?:dob|date_?of_?birth|ssn(?:_?last_?4)?|tax_?id|credit_?score|last_?name|first_?name|middle_?(?:name|init)|company_?name|addr(?:ess)?_?line[12]|city|state_?code|zip_?code|phone|email)[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\"',;}]+)");
  private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
  private static final Pattern EIN = Pattern.compile("\\b\\d{2}-\\d{7}\\b");

  private PiiLogScrubber() {}

  public static String scrub(String value) {
    if (value == null) {
      return null;
    }
    String scrubbed = maskSensitiveAssignments(value);
    scrubbed = SSN.matcher(scrubbed).replaceAll("****");
    return EIN.matcher(scrubbed).replaceAll("****");
  }

  private static String maskSensitiveAssignments(String value) {
    var matcher = SENSITIVE_VALUE.matcher(value);
    StringBuilder scrubbed = new StringBuilder();
    while (matcher.find()) {
      String sensitiveValue = matcher.group(2);
      String replacement =
          sensitiveValue.startsWith("\"") && sensitiveValue.endsWith("\"")
              ? "\"****\""
              : sensitiveValue.startsWith("'") && sensitiveValue.endsWith("'") ? "'****'" : "****";
      matcher.appendReplacement(
          scrubbed, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + replacement));
    }
    matcher.appendTail(scrubbed);
    return scrubbed.toString();
  }
}
