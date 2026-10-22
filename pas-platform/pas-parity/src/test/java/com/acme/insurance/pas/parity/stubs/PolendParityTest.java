package com.acme.insurance.pas.parity.stubs;

import com.acme.insurance.pas.parity.ParityHarness;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

@Disabled("AB-526: wire the policy endorsement service")
class PolendParityTest {
  @TestFactory
  Stream<DynamicTest> matchesCobolFixtures() {
    return ParityHarness.dynamicTests(
        new AbstractDisabledParityTest() {
          @Override
          public String program() {
            return "POLEND";
          }
        });
  }
}
