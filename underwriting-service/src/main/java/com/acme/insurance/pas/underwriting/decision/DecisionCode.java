package com.acme.insurance.pas.underwriting.decision;

/**
 * {@code WS-DECISION PIC X(02)} 88-levels and the reason text 5000-RENDER-DECISION moves to
 * {@code WS-DECISION-REASON}.
 */
public enum DecisionCode {
    /** 88 DEC-ACCEPT VALUE 'AP' - risk score below 300. */
    AP("AUTO-ACCEPTED: LOW RISK"),
    /** 88 DEC-REFER-SR VALUE 'RS' - risk score 300..599. */
    RS("REFERRED TO SENIOR UNDERWRITER"),
    /** 88 DEC-REFER-MGR VALUE 'RM' - risk score 600..799. */
    RM("REFERRED TO UW MANAGER"),
    /** 88 DEC-DECLINE VALUE 'DC' - risk score 800 and above. */
    DC("AUTO-DECLINED: HIGH RISK");

    private final String reason;

    DecisionCode(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
