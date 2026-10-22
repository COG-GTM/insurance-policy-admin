# POLNEW POLICY-RECORD golden

`new-business.json` is a hand-built 125-byte ASCII `POLICY-RECORD` sample for a new-business
referral. The fixture follows `cobol/copybooks/POLICY-RECORD.cpy`: fixed-width display fields,
signed packed-decimal premium fields, zero-initialized date/amount fields, and trailing filler.
`recordHex` is byte-for-byte output, not a decoded text representation.

`LegacyPolicyRecordsManualGoldenTest` checks the Java writer against this artifact. The separate
UNDWRT parity fixtures cover the decision record and the legacy three-digit risk-score field.
