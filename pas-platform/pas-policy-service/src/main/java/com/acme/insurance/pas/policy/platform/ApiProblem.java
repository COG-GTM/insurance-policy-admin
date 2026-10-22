package com.acme.insurance.pas.policy.platform;

import com.acme.insurance.pas.policy.newbusiness.NewBusinessError;
import java.util.List;

public record ApiProblem(
    String type, String title, int status, String detail, List<NewBusinessError> errors) {
  public ApiProblem {
    type = type == null ? "about:blank" : type;
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public static ApiProblem of(int status, String title, String detail) {
    return new ApiProblem("about:blank", title, status, detail, List.of());
  }
}
