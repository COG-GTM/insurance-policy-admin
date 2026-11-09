package com.acme.insurance.pas.underwriting.copybook;

/** Raised when a value does not fit the PIC clause of its copybook field. */
public class CopybookFieldException extends IllegalArgumentException {

    private final String field;

    public CopybookFieldException(String field, String message) {
        super(field + ": " + message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
