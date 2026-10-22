package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.policy.newbusiness.NewBusinessController;
import com.acme.insurance.pas.policy.newbusiness.NewBusinessRequest;
import com.acme.insurance.pas.policy.newbusiness.NewBusinessService;
import com.acme.insurance.pas.policy.underwriting.OverrideRequest;
import com.acme.insurance.pas.policy.underwriting.UnderwritingController;
import com.acme.insurance.pas.policy.underwriting.UnderwritingService;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class LegacyRouteModeTest {
  @Test
  void legacyNewBusinessAndUnderwritingReturn503WithoutCallingServices() {
    RouteFlags flags =
        RouteFlags.of(Map.of(RouteFlags.NEW_BUSINESS, "legacy", RouteFlags.UNDERWRITING, "legacy"));
    NewBusinessService newBusiness = mock(NewBusinessService.class);
    UnderwritingService underwriting = mock(UnderwritingService.class);
    NewBusinessController newBusinessController = new NewBusinessController(newBusiness, flags);
    UnderwritingController underwritingController = new UnderwritingController(underwriting, flags);
    Jwt jwt = jwt("policy:write underwriting:decide underwriting:override policy:read");

    assertThat(
            newBusinessController
                .create(
                    new NewBusinessRequest(
                        "AUT", "C000000001", LocalDate.now(), "AGT001", "B001", null, null, null),
                    null,
                    null,
                    jwt)
                .getStatusCode()
                .value())
        .isEqualTo(503);
    assertThat(underwritingController.decide("POL000000001", null, jwt).getStatusCode().value())
        .isEqualTo(503);
    assertThat(underwritingController.list("POL000000001", jwt).getStatusCode().value())
        .isEqualTo(503);
    assertThat(
            underwritingController
                .override("POL000000001", 1, new OverrideRequest("AP", "reason"), null, jwt)
                .getStatusCode()
                .value())
        .isEqualTo(503);
    verifyNoInteractions(newBusiness, underwriting);
  }

  private static Jwt jwt(String scopes) {
    return Jwt.withTokenValue("test-token")
        .header("alg", "none")
        .subject("route-test")
        .claim("scope", scopes)
        .build();
  }
}
