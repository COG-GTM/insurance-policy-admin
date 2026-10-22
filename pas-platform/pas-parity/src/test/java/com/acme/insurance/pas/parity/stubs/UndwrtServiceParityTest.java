package com.acme.insurance.pas.parity.stubs;

import com.acme.insurance.pas.parity.ParityHarness;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

@Disabled("AB-525: replace placeholder rules with the policy-service underwriting implementation")
class UndwrtServiceParityTest {
  @TestFactory
  Stream<DynamicTest> matchesCobolFixtures() {
    return ParityHarness.dynamicTests(
        new AbstractDisabledParityTest() {
          @Override
          public String program() {
            return "UNDWRT";
          }
        });
  }
}
