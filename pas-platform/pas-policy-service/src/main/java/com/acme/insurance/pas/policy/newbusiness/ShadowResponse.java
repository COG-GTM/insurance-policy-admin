package com.acme.insurance.pas.policy.newbusiness;

import java.util.List;

public record ShadowResponse(boolean shadow, String outcome, List<NewBusinessError> errors) {
  public ShadowResponse {
    errors = List.copyOf(errors);
  }
}
