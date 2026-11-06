/**
 * Fixed-width codecs for the legacy copybooks (POLICY-RECORD, COVERAGE-RECORD, PREMIUM-RECORD,
 * CUSTOMER-RECORD). Field order, offsets and lengths are a compatibility contract
 * (compat/check_layouts.py); {@link com.acme.insurance.pas.domain.copybook.CopybookLayouts} mirrors
 * cobol/copybooks and a unit test fails if the two drift.
 */
package com.acme.insurance.pas.domain.copybook;
