package com.acme.insurance.pas.common.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteFlagsTest {
  @Test
  void defaultsUnsetCapabilitiesAndAcceptsModernAlias() {
    RouteFlags flags = RouteFlags.of(Map.of(RouteFlags.POLICY_INQUIRY, "MoDeRn"));

    assertThat(flags.state(RouteFlags.POLICY_INQUIRY)).isEqualTo(RouteState.NEW);
    assertThat(flags.state(RouteFlags.NEW_BUSINESS)).isEqualTo(RouteState.LEGACY);
    assertThat(flags.servesTraffic(RouteFlags.POLICY_INQUIRY)).isTrue();
    assertThat(flags.asMap()).hasSize(10).containsEntry(RouteFlags.POLICY_INQUIRY, "new");
    assertThat(flags.asMap().keySet()).containsExactlyElementsOf(RouteFlags.CAPABILITIES);
  }

  @Test
  void parsesStatesCaseInsensitivelyAndServesRetiredRoutes() {
    RouteFlags flags =
        RouteFlags.of(
            Map.of(RouteFlags.POLICY_INQUIRY, "ReTiReD", RouteFlags.NEW_BUSINESS, "SHADOW"));

    assertThat(flags.state(RouteFlags.POLICY_INQUIRY)).isEqualTo(RouteState.RETIRED);
    assertThat(flags.servesTraffic(RouteFlags.POLICY_INQUIRY)).isTrue();
    assertThat(flags.state(RouteFlags.NEW_BUSINESS)).isEqualTo(RouteState.SHADOW);
  }

  @Test
  void normalizesMapKeysProducedByEnvironmentVariables() {
    RouteFlags flags =
        RouteFlags.of(
            Map.of(
                "daily.extract", "new",
                "monthly.exposure", "shadow",
                "premium.batch", "legacy"));

    assertThat(flags.state(RouteFlags.DAILY_EXTRACT)).isEqualTo(RouteState.NEW);
    assertThat(flags.state(RouteFlags.MONTHLY_EXPOSURE)).isEqualTo(RouteState.SHADOW);
    assertThat(flags.state(RouteFlags.PREMIUM_BATCH)).isEqualTo(RouteState.LEGACY);
  }

  @Test
  void rejectsConflictingCanonicalAndEnvironmentKeys() {
    assertThatThrownBy(
            () -> RouteFlags.of(Map.of("daily-extract", "legacy", "daily.extract", "new")))
        .hasMessage("Conflicting route flags for daily-extract");
  }

  @Test
  void rejectsUnknownCapabilitiesAndValuesWithTheCapabilityName() {
    assertThatThrownBy(() -> RouteFlags.of(Map.of("unknown", "new")))
        .hasMessageContaining("unknown");
    assertThatThrownBy(() -> RouteFlags.of(Map.of(RouteFlags.POLICY_INQUIRY, "bad")))
        .hasMessageContaining(RouteFlags.POLICY_INQUIRY);
    assertThatThrownBy(() -> RouteFlags.of(Map.of()).state("unknown"))
        .hasMessageContaining("unknown");
  }
}
