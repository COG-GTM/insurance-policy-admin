package com.acme.insurance.pas.policy.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

/** Q-27 fix: numbers are POL + 9 digits and numbers that already exist are skipped. */
class PolicyNumberGeneratorTest {

  static final String NEXTVAL = "SELECT nextval('pas.policy_seq')";
  static final String EXISTS = "SELECT EXISTS (SELECT 1 FROM pas.policies WHERE policy_number = ?)";

  @Test
  void skipsNumbersThatAlreadyExist() {
    JdbcOperations jdbc = mock(JdbcOperations.class);
    when(jdbc.queryForObject(NEXTVAL, Long.class)).thenReturn(1_000_000L, 1_000_001L);
    when(jdbc.queryForObject(eq(EXISTS), eq(Boolean.class), anyString())).thenReturn(false);
    when(jdbc.queryForObject(EXISTS, Boolean.class, "POL001000000")).thenReturn(true);
    assertThat(new PolicyNumberGenerator(jdbc).nextPolicyNumber()).isEqualTo("POL001000001");
  }

  @Test
  void givesUpAfterBoundedAttempts() {
    JdbcOperations jdbc = mock(JdbcOperations.class);
    when(jdbc.queryForObject(NEXTVAL, Long.class)).thenReturn(1_000_000L);
    when(jdbc.queryForObject(eq(EXISTS), eq(Boolean.class), anyString())).thenReturn(true);
    assertThatThrownBy(() -> new PolicyNumberGenerator(jdbc).nextPolicyNumber())
        .isInstanceOf(IllegalStateException.class);
  }
}
