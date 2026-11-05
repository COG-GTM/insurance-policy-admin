package com.acme.insurance.pas.underwriting;

/** Either a decision or the reason none was taken (nothing is written then). */
public sealed interface UnderwritingOutcome {

  /** A decision to write. */
  record Decided(RiskDecision decision) implements UnderwritingOutcome {}

  /** No decision; {@code reason.legacyMessage()} is the PUWR error text. */
  record Rejected(Rejection reason) implements UnderwritingOutcome {}

  /** Why no decision was taken, with the text UNDWRT sends for the same case. */
  enum Rejection {
    /**
     * The request carries no usable policy number. UNDWRT's failed map RECEIVE falls through and
     * writes a decision anyway (quirk Q-520-1); the port stops here.
     */
    INVALID_REQUEST("ERROR RECEIVING UNDERWRITING DATA"),
    /** Policy or holder not found, or not visible to the caller. */
    NOT_FOUND("POLICY/CUSTOMER DATA NOT FOUND");

    private final String legacyMessage;

    Rejection(String legacyMessage) {
      this.legacyMessage = legacyMessage;
    }

    public String legacyMessage() {
      return legacyMessage;
    }
  }
}
