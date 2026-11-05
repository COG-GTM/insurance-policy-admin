package com.acme.insurance.pas.domain.copybook;

import com.acme.insurance.pas.domain.Policy;
import java.nio.charset.Charset;

/** POLICY-RECORD (125 bytes). */
public final class PolicyRecordCodec extends CopybookCodec<Policy> {

  public PolicyRecordCodec() {
    this(EBCDIC);
  }

  public PolicyRecordCodec(Charset charset) {
    super(CopybookLayouts.POLICY_RECORD, charset);
  }

  @Override
  protected Policy read(RecordReader in) {
    return new Policy(
        in.text("POLICY-NUMBER"),
        in.text("POLICY-TYPE"),
        in.text("POLICY-STATUS"),
        in.date("POLICY-EFFECTIVE-DATE"),
        in.date("POLICY-EXPIRY-DATE"),
        in.text("POLICY-HOLDER-ID"),
        in.text("POLICY-AGENT-CODE"),
        in.text("POLICY-BRANCH-CODE"),
        in.decimal("POLICY-TOTAL-PREMIUM"),
        in.decimal("POLICY-DEDUCTIBLE"),
        in.decimal("POLICY-LIMIT"),
        in.date("POLICY-INCEPTION-DATE"),
        in.number("POLICY-RENEWAL-COUNT"),
        in.text("POLICY-UW-STATUS"),
        in.number("POLICY-RISK-SCORE"),
        in.text("POLICY-WEB-IND"),
        in.text("POLICY-API-FLAG"),
        in.dateAsTimestamp("POLICY-LAST-UPDATED"),
        in.text("POLICY-UPDATED-BY"));
  }

  @Override
  protected void write(Policy p, RecordWriter out) {
    out.text("POLICY-NUMBER", p.policyNumber())
        .text("POLICY-TYPE", p.policyTypeCode())
        .text("POLICY-STATUS", p.policyStatusCode())
        .date("POLICY-EFFECTIVE-DATE", p.effectiveDate())
        .date("POLICY-EXPIRY-DATE", p.expiryDate())
        .text("POLICY-HOLDER-ID", p.policyholderId())
        .text("POLICY-AGENT-CODE", p.agentCode())
        .text("POLICY-BRANCH-CODE", p.branchCode())
        .decimal("POLICY-TOTAL-PREMIUM", p.totalPremium())
        .decimal("POLICY-DEDUCTIBLE", p.deductible())
        .decimal("POLICY-LIMIT", p.coverageLimit())
        .date("POLICY-INCEPTION-DATE", p.inceptionDate())
        .number("POLICY-RENEWAL-COUNT", p.renewalCount())
        .text("POLICY-UW-STATUS", p.uwStatusCode())
        .number("POLICY-RISK-SCORE", p.riskScore())
        .text("POLICY-WEB-IND", p.webIndicator())
        .text("POLICY-API-FLAG", p.apiFlag())
        .dateFromTimestamp("POLICY-LAST-UPDATED", p.lastUpdated())
        .text("POLICY-UPDATED-BY", p.updatedBy());
  }
}
