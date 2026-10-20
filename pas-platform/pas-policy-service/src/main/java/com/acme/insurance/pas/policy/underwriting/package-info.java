/**
 * Underwriting decisions (AB-525, replaces UNDWRT): {@link
 * com.acme.insurance.pas.policy.underwriting.UnderwritingRiskScorer} is the parity port of the risk
 * score; {@link com.acme.insurance.pas.policy.underwriting.UnderwritingService} records decisions
 * and manual overrides and publishes underwriting events through the outbox.
 */
package com.acme.insurance.pas.policy.underwriting;
