package com.acme.insurance.pas.parity.stubs;

import com.acme.insurance.pas.parity.ParityHarness;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

@Disabled("AB-522: wire pas-rating premium calculation")
class PrembatParityTest {
  @TestFactory
  Stream<DynamicTest> matchesCobolFixtures() {
    return ParityHarness.dynamicTests(
        new AbstractDisabledParityTest() {
          @Override
          public String program() {
            return "PREMBAT";
          }
        });
  }
}
