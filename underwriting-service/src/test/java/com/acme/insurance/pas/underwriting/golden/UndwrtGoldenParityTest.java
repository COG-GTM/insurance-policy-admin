package com.acme.insurance.pas.underwriting.golden;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Golden parity suite for UNDWRT.cbl. Every expected value was derived by hand-tracing the COBOL
 * paragraphs (IBM Enterprise COBOL semantics: no ROUNDED, no ON SIZE ERROR, unsigned receivers drop
 * the sign) - not by running the Java. Business date is 2026-11-09, so the loss-history window
 * starts 2021-11-09. Each case's comment cites the paragraph(s) it exercises and the trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UndwrtGoldenParityTest {

    private static final String NOT_FOUND = "POLICY/CUSTOMER DATA NOT FOUND";
    private static final String RECEIVE_ERROR = "ERROR RECEIVING UNDERWRITING DATA";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    record Expect(int httpStatus, String code, Integer riskScore, Integer policyRiskScore, String message) {
        static Expect decision(String code, int riskScore) {
            return new Expect(200, code, riskScore, riskScore % 1000, reason(code));
        }

        static Expect decision(String code, int riskScore, int policyRiskScore) {
            return new Expect(200, code, riskScore, policyRiskScore, reason(code));
        }

        static Expect error(int httpStatus, String message) {
            return new Expect(httpStatus, null, null, null, message);
        }

        private static String reason(String code) {
            return switch (code) {
                case "AP" -> "AUTO-ACCEPTED: LOW RISK";
                case "RS" -> "REFERRED TO SENIOR UNDERWRITER";
                case "RM" -> "REFERRED TO UW MANAGER";
                default -> "AUTO-DECLINED: HIGH RISK";
            };
        }
    }

    static Arguments golden(String id, Scenario scenario, Expect expect) {
        return Arguments.of(id, scenario, expect);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // UW-01 3000 credit>0: (900-750)/2 = 75; 5000 WHEN <300 -> AP
                golden("UW-01", Scenario.base().credit(750), Expect.decision("AP", 75)),
                // UW-02 3000 COMPUTE truncation (no ROUNDED): (900-651)/2 = 124.5 -> 124; 5000 -> AP
                golden("UW-02", Scenario.base().credit(651), Expect.decision("AP", 124)),
                // UW-03 3000 ELSE (credit = 0): MOVE 400; 5000 WHEN <600 -> RS
                golden("UW-03", Scenario.base().credit(0), Expect.decision("RS", 400)),
                // UW-04 3000 credit>0 boundary: (900-1)/2 = 449.5 -> 449; 5000 -> RS
                golden("UW-04", Scenario.base().credit(1), Expect.decision("RS", 449)),
                // UW-05 3000 credit>900: (900-950)/2 = -25 into unsigned PIC 9(04) -> 25; 5000 -> AP
                golden("UW-05", Scenario.base().credit(950), Expect.decision("AP", 25)),
                // UW-06 3000 credit 999 (PIC 9(03) max): -99/2 = -49.5 -> -49 -> sign dropped -> 49; AP
                golden("UW-06", Scenario.base().credit(999), Expect.decision("AP", 49)),
                // UW-07 3000 CUST-TIER-SUBSTAND: 100 + 200 = 300; 5000 boundary 300 is not <300 -> RS
                golden("UW-07", Scenario.base().tier("U"), Expect.decision("RS", 300)),
                // UW-08 3000 CUST-TIER-PREFERRED: (900-500)/2 = 200 - 100 = 100; AP
                golden("UW-08", Scenario.base().credit(500).tier("P"), Expect.decision("AP", 100)),
                // UW-09 3000 PREFERRED underflow: 25 - 100 = -75 into unsigned 9(04) -> 75; AP
                golden("UW-09", Scenario.base().credit(850).tier("P"), Expect.decision("AP", 75)),
                // UW-10 3000 PREFERRED from zero: (900-900)/2 = 0 - 100 = -100 -> 100; AP
                golden("UW-10", Scenario.base().credit(900).tier("P"), Expect.decision("AP", 100)),
                // UW-11 3000 claim count 3 (not >3): 100, LR 3.00; AP
                golden("UW-11", Scenario.base().claims(3, "10.00"), Expect.decision("AP", 100)),
                // UW-12 3000 claim count 4 (>3): (900-600)/2 = 150 + 150 = 300; RS
                golden("UW-12", Scenario.base().credit(600).claims(4, "10.00"), Expect.decision("RS", 300)),
                // UW-13 3000 claim count 5 (>3, not >5): 100 + 150 = 250; AP
                golden("UW-13", Scenario.base().claims(5, "10.00"), Expect.decision("AP", 250)),
                // UW-14 3000 claim count 6 (>3 and >5, cumulative): 100 + 150 + 200 = 450; RS
                golden("UW-14", Scenario.base().claims(6, "10.00"), Expect.decision("RS", 450)),
                // UW-15 3000 CLAIM_DATE >= CURRENT DATE - 5 YEARS: 6 claims on 2021-11-08 are excluded -> 100; AP
                golden("UW-15", outsideWindow(Scenario.base(), 6), Expect.decision("AP", 100)),
                // UW-16 3000 window boundary inclusive: 3 in window + 1 on 2021-11-09 = 4 -> 100 + 150 = 250; AP
                golden("UW-16", Scenario.base().claims(3, "10.00").claimOn(LocalDate.of(2021, 11, 9), "10.00"),
                        Expect.decision("AP", 250)),
                // UW-17 3000 loss ratio = 80.00 exactly (800/1000*100): not >80 -> 100; AP
                golden("UW-17", Scenario.base().claims(1, "800.00"), Expect.decision("AP", 100)),
                // UW-18 3000 IBM intermediate precision: 805/1000 = 0.805 kept to dmax=2 -> 0.80 * 100 = 80.00,
                //       not >80 (exact maths would give 80.50) -> 100; AP
                golden("UW-18", Scenario.base().claims(1, "805.00"), Expect.decision("AP", 100)),
                // UW-19 3000 loss ratio 81.00 > 80: 100 + 200 = 300; RS
                golden("UW-19", Scenario.base().claims(1, "810.00"), Expect.decision("RS", 300)),
                // UW-20 3000 WS-LOSS-RATIO S9(03)V99 high-order truncation: 10500/1000*100 = 1050.00 -> 050.00,
                //       not >80 -> 100; AP
                golden("UW-20", Scenario.base().claims(1, "10500.00"), Expect.decision("AP", 100)),
                // UW-21 3000 high-order truncation still over 80: 1250.00 -> 250.00 > 80 -> 300; RS
                golden("UW-21", Scenario.base().claims(1, "12500.00"), Expect.decision("RS", 300)),
                // UW-22 3000 POLICY-TOTAL-PREMIUM = 0: loss-ratio block skipped despite 5000 incurred -> 100; AP
                golden("UW-22", Scenario.base().premium("0.00").claims(1, "5000.00"), Expect.decision("AP", 100)),
                // UW-23 3000 limit = 5,000,000.00 (not >): 100; AP
                golden("UW-23", Scenario.base().limit("5000000.00"), Expect.decision("AP", 100)),
                // UW-24 3000 limit 5,000,000.01 (>5M): 100 + 100 = 200; AP
                golden("UW-24", Scenario.base().limit("5000000.01"), Expect.decision("AP", 200)),
                // UW-25 3000 limit = 10,000,000.00 (>5M, not >10M): 200; AP
                golden("UW-25", Scenario.base().limit("10000000.00"), Expect.decision("AP", 200)),
                // UW-26 3000 limit 10,000,000.01 (>5M and >10M): 100 + 100 + 200 = 400; RS
                golden("UW-26", Scenario.base().limit("10000000.01"), Expect.decision("RS", 400)),
                // UW-27 4000 branch accumulation = 500,000,000.00 (not >; own policy is PN so excluded): 100; AP
                golden("UW-27", Scenario.base().otherPolicy("B001", "AC", "500000000.00"), Expect.decision("AP", 100)),
                // UW-28 4000 accumulation > 500M: 100 + 300 = 400; 5000 overwrites the
                //       'ACCUMULATION LIMIT EXCEEDED' reason with the RS text
                golden("UW-28", Scenario.base().otherPolicy("B001", "AC", "500000000.01"), Expect.decision("RS", 400)),
                // UW-29 4000 own policy counts when POLICY_STATUS = 'AC': 499,950,000 + 100,000 > 500M -> 400; RS
                golden("UW-29", Scenario.base().status("AC").otherPolicy("B001", "AC", "499950000.00"),
                        Expect.decision("RS", 400)),
                // UW-30 4000 only AC policies in the same branch: CN in B001 and AC in B999 ignored -> 100; AP
                golden("UW-30", Scenario.base().otherPolicy("B001", "CN", "600000000.00")
                        .otherPolicy("B999", "AC", "600000000.00"), Expect.decision("AP", 100)),
                // UW-31 5000 boundary 299: (900-301)/2 = 299.5 -> 299; AP
                golden("UW-31", Scenario.base().credit(301), Expect.decision("AP", 299)),
                // UW-32 5000 boundary 600: 100 + 200 (U) + 300 (4000) = 600 -> RM
                golden("UW-32", Scenario.base().tier("U").otherPolicy("B001", "AC", "500000000.01"),
                        Expect.decision("RM", 600)),
                // UW-33 5000 boundary 799: 299 + 200 (U) + 300 (limit >10M) = 799 -> RM
                golden("UW-33", Scenario.base().credit(301).tier("U").limit("10000000.01"), Expect.decision("RM", 799)),
                // UW-34 5000 boundary 800: (900-300)/2 = 300 + 200 + 300 = 800 -> WHEN OTHER -> DC
                golden("UW-34", Scenario.base().credit(300).tier("U").limit("10000000.01"), Expect.decision("DC", 800)),
                // UW-35 3000+4000+5000 every surcharge: 400 + 200 + 150 + 200 + 200 (LR 1200/1000 = 120.00)
                //       + 300 (limit) + 300 (accum) = 1750 -> DC; MOVE to POLICY-RISK-SCORE 9(03) keeps 750
                golden("UW-35", Scenario.base().credit(0).tier("U").claims(6, "200.00").limit("10000000.01")
                        .otherPolicy("B001", "AC", "500000000.01"), Expect.decision("DC", 1750, 750)),
                // UW-36 2000 SQLCODE +100: no POLICIES row -> 9000-SEND-ERROR
                golden("UW-36", Scenario.base().noPolicy(), Expect.error(404, NOT_FOUND)),
                // UW-37 2000 SQLCODE +100: JOIN finds no POLICY_HOLDERS row
                golden("UW-37", Scenario.base().noHolder(), Expect.error(404, NOT_FOUND)),
                // UW-38 2000 SQLCODE -305: CREDIT_SCORE NULL, no indicator variable
                golden("UW-38", Scenario.base().credit(null), Expect.error(404, NOT_FOUND)),
                // UW-39 2000 SQLCODE -305: TOTAL_PREMIUM NULL
                golden("UW-39", Scenario.base().premium(null), Expect.error(404, NOT_FOUND)),
                // UW-40 2000 SQLCODE -305: RISK_TIER NULL
                golden("UW-40", Scenario.base().tier(null), Expect.error(404, NOT_FOUND)),
                // UW-41 2000 SQLCODE -305: BRANCH_CODE NULL
                golden("UW-41", Scenario.base().branch(null), Expect.error(404, NOT_FOUND)),
                // UW-42 2000 SQLCODE -304: CREDIT_SCORE 1000 does not fit PIC 9(03)
                golden("UW-42", Scenario.base().credit(1000), Expect.error(404, NOT_FOUND)),
                // UW-43 2000 RECEIVE MAPFAIL: blank policy number
                golden("UW-43", Scenario.base().request("   "), Expect.error(400, RECEIVE_ERROR)),
                // UW-44 2000 RECEIVE: 13 characters cannot be entered into POLICY-NUMBER PIC X(12)
                golden("UW-44", Scenario.base().request("POL-UW0000012"), Expect.error(400, RECEIVE_ERROR)),
                // UW-45 2000 RECEIVE: no policy number field at all
                golden("UW-45", Scenario.base().requestBody("{}"), Expect.error(400, RECEIVE_ERROR)),
                // UW-46 2000 RECEIVE: unreadable input
                golden("UW-46", Scenario.base().requestBody("not-json"), Expect.error(400, RECEIVE_ERROR)),
                // UW-47 2000 policy number shorter than 12 is blank-padded like PIC X(12): matches 'POL-UW000001'
                //       only when equal after padding; trailing blanks in the request are ignored -> AP 75
                golden("UW-47", Scenario.base().credit(750).request("POL-UW000001  "), Expect.decision("AP", 75)));
    }

    private static Scenario outsideWindow(Scenario s, int count) {
        for (int i = 0; i < count; i++) {
            s.claimOn(LocalDate.of(2021, 11, 8), "10.00");
        }
        return s;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesCobol(String id, Scenario scenario, Expect expect) throws Exception {
        scenario.load(jdbc);
        ResultActions result = mvc.perform(post("/api/v1/underwriting/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scenario.requestBody))
                .andExpect(status().is(expect.httpStatus()))
                .andExpect(jsonPath("$.transactionId").value("PUWR"));
        if (expect.code() == null) {
            result.andExpect(jsonPath("$.errorMessage").value(expect.message()));
            return;
        }
        result.andExpect(jsonPath("$.policyNumber").value(Scenario.POLICY))
                .andExpect(jsonPath("$.decisionCode").value(expect.code()))
                .andExpect(jsonPath("$.decisionReason").value(expect.message()))
                .andExpect(jsonPath("$.riskScore").value(expect.riskScore()))
                .andExpect(jsonPath("$.policyRiskScore").value(expect.policyRiskScore()))
                .andExpect(jsonPath("$.decisionDate").value("2026-11-09"));
    }
}
