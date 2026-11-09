package com.acme.insurance.pas.underwriting.golden;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** DB2 rows for one golden case. Defaults: credit 700 / tier S / premium 1,000 / limit 100,000 / PN. */
final class Scenario {

    static final String POLICY = "POL-UW000001";
    static final String HOLDER = "CUST-UW001";
    static final String BRANCH = "B001";
    static final LocalDate IN_WINDOW = LocalDate.of(2026, 1, 15);

    record Claim(LocalDate date, BigDecimal amount) {
    }

    record OtherPolicy(String number, String branch, String status, BigDecimal limit) {
    }

    boolean insertPolicy = true;
    boolean insertHolder = true;
    String status = "PN";
    BigDecimal premium = new BigDecimal("1000.00");
    BigDecimal limit = new BigDecimal("100000.00");
    String branch = BRANCH;
    Integer credit = 700;
    String tier = "S";
    final List<Claim> claims = new ArrayList<>();
    final List<OtherPolicy> others = new ArrayList<>();
    String requestBody = "{\"policyNumber\":\"" + POLICY + "\"}";

    static Scenario base() {
        return new Scenario();
    }

    Scenario credit(Integer v) { credit = v; return this; }
    Scenario tier(String v) { tier = v; return this; }
    Scenario premium(String v) { premium = v == null ? null : new BigDecimal(v); return this; }
    Scenario limit(String v) { limit = new BigDecimal(v); return this; }
    Scenario status(String v) { status = v; return this; }
    Scenario branch(String v) { branch = v; return this; }
    Scenario noPolicy() { insertPolicy = false; return this; }
    Scenario noHolder() { insertHolder = false; return this; }
    Scenario requestBody(String json) { requestBody = json; return this; }
    Scenario request(String policyNumber) { return requestBody("{\"policyNumber\":\"" + policyNumber + "\"}"); }

    Scenario claims(int count, String amountEach) {
        for (int i = 0; i < count; i++) {
            claims.add(new Claim(IN_WINDOW.plusDays(i), new BigDecimal(amountEach)));
        }
        return this;
    }

    Scenario claimOn(LocalDate date, String amount) {
        claims.add(new Claim(date, new BigDecimal(amount)));
        return this;
    }

    Scenario otherPolicy(String branch, String status, String limit) {
        others.add(new OtherPolicy(String.format("POL-OT%06d", others.size() + 1), branch, status, new BigDecimal(limit)));
        return this;
    }

    void load(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM ACMEINS.CLAIMS");
        jdbc.update("DELETE FROM ACMEINS.UNDERWRITING_DECISIONS");
        jdbc.update("DELETE FROM ACMEINS.POLICIES");
        jdbc.update("DELETE FROM ACMEINS.POLICY_HOLDERS");
        if (insertHolder) {
            jdbc.update("INSERT INTO ACMEINS.POLICY_HOLDERS (CUST_ID, CUST_TYPE, CREDIT_SCORE, RISK_TIER) VALUES (?, 'C', ?, ?)",
                    HOLDER, credit, tier);
        }
        if (insertPolicy) {
            insertPolicy(jdbc, POLICY, branch, status, limit, premium);
        }
        for (OtherPolicy o : others) {
            insertPolicy(jdbc, o.number(), o.branch(), o.status(), o.limit(), new BigDecimal("1000.00"));
        }
        for (Claim c : claims) {
            jdbc.update("INSERT INTO ACMEINS.CLAIMS (POLICY_NUMBER, CLAIM_DATE, INCURRED_AMOUNT) VALUES (?, ?, ?)",
                    POLICY, c.date(), c.amount());
        }
    }

    private static void insertPolicy(JdbcTemplate jdbc, String number, String branch, String status,
            BigDecimal limit, BigDecimal premium) {
        jdbc.update("""
                INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, EFFECTIVE_DATE, EXPIRY_DATE,
                       POLICYHOLDER_ID, BRANCH_CODE, TOTAL_PREMIUM, COVERAGE_LIMIT)
                VALUES (?, 'COM', ?, DATE '2026-12-01', DATE '2027-12-01', ?, ?, ?, ?)
                """, number, status, HOLDER, branch, premium, limit);
    }
}
