package com.acme.insurance.pas.batch.migration.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TriageServiceTest {
  @Test
  void approvedFixRequiresAnExistingFixQuirkAndOperator() {
    TriageService service = new TriageService(mock(JdbcTemplate.class), new SimpleMeterRegistry());

    assertThatThrownBy(() -> service.classify(1, "APPROVED_FIX", "Q-04", null, "operator"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("decision contains Fix");
    assertThatThrownBy(() -> service.classify(1, "APPROVED_FIX", "Q-99", null, "operator"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("decision contains Fix");
    assertThatThrownBy(() -> service.classify(1, "APPROVED_FIX", "Q-01", null, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Operator identity is required");
  }

  @Test
  void bugAndDataIssueRequireNotesAndFixQuirkIsAccepted() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(null);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    TriageService service = new TriageService(jdbc, new SimpleMeterRegistry());

    assertThatThrownBy(() -> service.classify(1, "BUG", null, " ", "operator"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonblank note");
    assertThatThrownBy(() -> service.classify(1, "DATA_ISSUE", null, null, "operator"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonblank note");
    assertThat(service.classify(1, "APPROVED_FIX", "Q-01", null, "operator").classification())
        .isEqualTo("APPROVED_FIX");
  }
}
