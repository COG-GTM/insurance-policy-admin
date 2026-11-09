# UNDWRT (CICS PUWR) -> `underwriting-service` migration

Source: `cobol/programs/UNDWRT.cbl`, copybooks `POLICY-RECORD.cpy` / `CUSTOMER-RECORD.cpy`, DB2 DDL
`sql/ddl/create-tables.sql`. Target: `underwriting-service/` (Java 17, Spring Boot 3.3, Spring Data JDBC).
The legacy COBOL, copybooks, DDL and the Java 8 `java-facade/` are unchanged.

REST: `POST /api/v1/underwriting/decisions` with `{"policyNumber": "POL-00000001"}`.

| CICS outcome | HTTP | Body |
|---|---|---|
| 8000-DISPLAY-RESULT | 200 | `transactionId` `PUWR`, `policyNumber`, `decisionCode` (AP/RS/RM/DC), `decisionReason`, `riskScore` (WS-RISK-SCORE 9(04)), `policyRiskScore` (POLICY-RISK-SCORE 9(03)), `decisionDate` |
| 9000-SEND-ERROR, RECEIVE not NORMAL | 400 | `errorMessage`: `ERROR RECEIVING UNDERWRITING DATA` |
| 9000-SEND-ERROR, SELECT SQLCODE not 0 | 404 | `errorMessage`: `POLICY/CUSTOMER DATA NOT FOUND` |

Decision codes and texts are exactly the COBOL literals: `AP` `AUTO-ACCEPTED: LOW RISK` (score < 300),
`RS` `REFERRED TO SENIOR UNDERWRITER` (< 600), `RM` `REFERRED TO UW MANAGER` (< 800),
`DC` `AUTO-DECLINED: HIGH RISK` (otherwise).

## Paragraph mapping

All Java methods are in `UnderwritingDecisionService` unless qualified. Test IDs `UW-nn` are the cases in
`golden/UndwrtGoldenParityTest` (each has a comment with the paragraph and hand trace); other tests are
named by class.

| COBOL paragraph / statement | Java | Tests |
|---|---|---|
| `0000-MAIN-LOGIC` (IF WS-ERROR-MSG = SPACES) | `decide(String)` | all UW-01..UW-47 |
| `1000-INITIALIZE` (MOVE SPACES/0, ASKTIME + FORMATTIME YYYYMMDD) | `initialize(WorkArea)`, `UnderwritingConfiguration.underwritingClock` | all (business date 2026-11-09), `UnderwritingSideEffectsTest.writesAuditRowAndUpdatesPolicy` |
| `2000` RECEIVE MAP UWRTMAP / RESP not NORMAL | `receiveMap(String)`, `readPolicyData`, `UnderwritingController.unreadable` | UW-43, UW-44, UW-45, UW-46, UW-47 |
| `2000` SELECT POLICIES JOIN POLICY_HOLDERS, SQLCODE not 0 | `readPolicyData`, `UnderwritingRepository.selectPolicyAndCustomer`, `PolicyCustomerRow.hasNullColumn` | UW-36..UW-42, `UnderwritingSideEffectsTest.notFoundWritesNothing` |
| `2000` host variables INTO POLICY-RECORD / CUSTOMER-RECORD | `PolicyRecord.fromUnderwritingSelect`, `CustomerRecord.fromUnderwritingSelect` | `CopybookRecordsTest` (9 tests), UW-42 |
| `3000` credit > 0: COMPUTE (900 - credit) / 2 | `calculateRiskScore`, `CobolArithmetic.divideTruncated`, `storeUnsigned` | UW-01, UW-02, UW-04, UW-05, UW-06, UW-31 |
| `3000` ELSE MOVE 400 | `calculateRiskScore` | UW-03, UW-35 |
| `3000` CUST-TIER-SUBSTAND ADD 200 | `calculateRiskScore` | UW-07, UW-32, UW-33, UW-34, UW-35 |
| `3000` CUST-TIER-PREFERRED SUBTRACT 100 | `calculateRiskScore`, `storeUnsigned` | UW-08, UW-09, UW-10 |
| `3000` claims COUNT/SUM, last 5 years | `UnderwritingRepository.selectClaimsHistory` | UW-15, UW-16 |
| `3000` claim count > 3 ADD 150 / > 5 ADD 200 | `calculateRiskScore` | UW-11, UW-12, UW-13, UW-14, UW-35 |
| `3000` IF premium > 0, COMPUTE WS-LOSS-RATIO, > 80 ADD 200 | `calculateRiskScore`, `CobolArithmetic.divideIntermediate`, `storeSigned` | UW-17..UW-22, UW-35, `CobolArithmeticTest` |
| `3000` limit > 5M ADD 100 / > 10M ADD 200 | `calculateRiskScore` | UW-23..UW-26, UW-33, UW-34, UW-35 |
| `4000-CHECK-ACCUMULATION` SUM(COVERAGE_LIMIT) branch, status AC | `checkAccumulation`, `UnderwritingRepository.selectBranchAccumulation` | UW-27, UW-29, UW-30 |
| `4000` > 500M ADD 300 + STRING reason | `checkAccumulation` | UW-28, UW-29, UW-32, UW-35, `UnderwritingSideEffectsTest.accumulationReasonIsBuiltThenOverwrittenBy5000` |
| `5000-RENDER-DECISION` EVALUATE TRUE | `renderDecision`, `DecisionCode` | AP: UW-01, UW-31; RS: UW-03, UW-07; RM: UW-32, UW-33; DC: UW-34, UW-35 |
| `5000` MOVE WS-RISK-SCORE TO POLICY-RISK-SCORE, MOVE WS-DECISION TO POLICY-UW-STATUS | `renderDecision`, `CobolArithmetic.moveUnsigned`, `PolicyRecord.withDecision` | UW-35 (1750 -> 750), `CopybookRecordsTest.withDecisionSetsUwStatusAndThreeDigitRiskScore` |
| `6000-WRITE-DECISION` INSERT UNDERWRITING_DECISIONS, UPDATE POLICIES (no SQLCODE check) | `writeDecision`, `UnderwritingRepository.insertDecision`, `updatePolicyDecision` | `UnderwritingSideEffectsTest.writesAuditRowAndUpdatesPolicy`, `secondDecisionSameDayKeepsFirstAuditRowButStillUpdatesPolicy` |
| `7000-SEND-MQ-NOTIFICATION` WRITEQ TD (RESP not tested) | `sendMqNotification`, `UnderwritingNotificationPublisher` | `UnderwritingSideEffectsTest.publishesPolicyRecordWithDecisionToQueue`, `queueFailureDoesNotChangeTheDecisionOrRollBack` |
| `8000-DISPLAY-RESULT` SEND MAP UWRTRES | `displayResult`, `UnderwritingController.decide` (200) | all decision cases |
| `9000-SEND-ERROR` SEND TEXT LENGTH(79) | `sendError`, `UnderwritingController.decide` (400/404) | UW-36..UW-46 |
| `9999-RETURN` (end of unit of work, syncpoint) | end of `@Transactional decide` | `UnderwritingSideEffectsTest` |

## Numeric semantics

| COBOL | Java |
|---|---|
| `WS-RISK-SCORE PIC 9(04)` unsigned: negative results lose the sign, > 9999 loses high-order digits (no ON SIZE ERROR) | `CobolArithmetic.storeUnsigned(v, 4)` after every COMPUTE/ADD/SUBTRACT (UW-05, UW-06, UW-09, UW-10) |
| `COMPUTE` without ROUNDED truncates | integer division toward zero (UW-02, UW-04, UW-31) |
| `(WS-TOTAL-INCURRED / POLICY-TOTAL-PREMIUM) * 100` | quotient truncated to 2 decimals (IBM Enterprise COBOL intermediate for division, dmax = 2), then * 100 (UW-18) |
| `WS-LOSS-RATIO PIC S9(03)V99 COMP-3` | `storeSigned(v, 3, 2)`: truncates low decimals and high-order integer digits (UW-20, UW-21) |
| `POLICY-RISK-SCORE PIC 9(03)` receiving a 9(04) | `moveUnsigned(v, 3)` keeps low-order 3 digits (UW-35) |
| `WS-CLAIM-COUNT PIC 9(05)` | `storeUnsigned(count, 5)` |
| COMP-3 copybook fields (`POLICY-TOTAL-PREMIUM` S9(9)V99, `POLICY-DEDUCTIBLE` S9(7)V99, `POLICY-LIMIT` S9(11)V99) | `BigDecimal`, scale and integer digits validated (`CopybookFieldException`) |

Copybook records: `PolicyRecord` (125 bytes incl. 20-byte FILLER) and `CustomerRecord` (365 bytes incl.
15-byte FILLER); offsets are in the Javadoc and match `compat/check_layouts.py`. FILLER is not mapped.
Every PIC X(n) / 9(n) / COMP-3 field is length- or range-checked in the record's compact constructor.

## Behaviour not preserved, or preserved with assumptions

1. **IBM MQ / TD queue.** `WRITEQ TD QUEUE('ACME.PAS.UNDERWRITING.DECISION')` is behind
   `UnderwritingNotificationPublisher`; the default `LoggingNotificationPublisher` only logs. A JMS/MQ
   implementation needs the MQ connection details, which are not in the repository. The fire-and-forget
   semantics (RESP never tested) are preserved.
2. **BMS maps.** UWRTMAP/UWRTRES screens are replaced by JSON. Only POLICY-NUMBER is read from the map,
   as in UNDWRT. MAPFAIL is modelled as a blank/missing/over-length policy number. `9999-RETURN`'s
   pseudo-conversational `TRANSID('PUWR') COMMAREA` has no REST equivalent; each request is independent.
3. **`CLAIMS` table.** UNDWRT reads `ACMEINS.CLAIMS` but it is not in `sql/ddl/create-tables.sql` (it
   belongs to the Claims system). Tests use `h2/claims-stub.sql` with only the columns UNDWRT references.
4. **Decision date host variable.** UNDWRT inserts `WS-CURRENT-DATE PIC 9(08)` (YYYYMMDD) into the DB2
   `DATE` column `DECISION_DATE`; the Java service binds a `LocalDate`. Also `ASKTIME ABSTIME` targets a
   PIC 9(08) field (ABSTIME is S9(15) COMP-3); only the FORMATTIME YYYYMMDD result is used, which the
   Java `Clock` (zone `pas.underwriting.zone`, default America/New_York) reproduces.
5. **Ignored SQL errors in 6000.** UNDWRT never checks SQLCODE after INSERT/UPDATE, so a second decision
   on the same day (-803 on PK `POLICY_NUMBER, DECISION_DATE`) keeps the first audit row but still updates
   `POLICIES` and returns the new decision. Java does the same (warning logged).
6. **SQLCODE -305 / -304 / -811 in 2000.** UNDWRT has no indicator variables, so a NULL in any selected
   column, a CREDIT_SCORE that does not fit PIC 9(03), or a duplicate join row gives SQLCODE != 0. Java maps
   all of these to `POLICY/CUSTOMER DATA NOT FOUND` (UW-38..UW-42).
7. **Intermediate precision.** The loss-ratio intermediate assumes IBM Enterprise COBOL defaults
   (ARITH(COMPAT), no ROUNDED). A different compiler option set could change UW-18 only.
8. **`WS-ZONE-RISK-FACTOR`** is declared but never used by UNDWRT; it is not ported.
9. **Accumulation reason.** 4000 builds `ACCUMULATION LIMIT EXCEEDED FOR BRANCH xxxx`, but 5000 always
   overwrites WS-DECISION-REASON, so that text is never persisted or shown. Ported as-is (no behaviour change).

## Running

```
cd underwriting-service
mvn verify                                   # 86 tests incl. 47 golden cases (H2, DB2 mode)
mvn spring-boot:run -Dspring-boot.run.profiles=local   # H2 + sample data on :8080
```

Production uses DB2 via `PAS_DB_URL`, `PAS_DB_USERNAME`, `PAS_DB_PASSWORD` (no credentials in config).
