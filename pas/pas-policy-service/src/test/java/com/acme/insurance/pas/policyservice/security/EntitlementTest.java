package com.acme.insurance.pas.policyservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class EntitlementTest {

  @Test
  void wholeBookSeesEveryPolicy() {
    assertThat(new Entitlement(true, Set.of(), Set.of()).permits("AG1", "B1")).isTrue();
  }

  @Test
  void agentOrBranchMatchGrantsAccess() {
    Entitlement e = new Entitlement(false, Set.of("AG1"), Set.of("CHI1"));
    assertThat(e.permits("AG1", "DET1")).isTrue();
    assertThat(e.permits("AG9", "CHI1")).isTrue();
    assertThat(e.permits("AG9", "DET1")).isFalse();
  }

  @Test
  void nullCodesAndNoEntitlementGrantNothing() {
    assertThat(Entitlement.NONE.permits("AG1", "CHI1")).isFalse();
    assertThat(new Entitlement(false, Set.of("AG1"), Set.of("CHI1")).permits(null, null)).isFalse();
  }
}
