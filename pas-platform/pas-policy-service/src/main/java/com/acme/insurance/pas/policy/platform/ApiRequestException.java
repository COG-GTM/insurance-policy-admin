package com.acme.insurance.pas.policy.platform;

import com.acme.insurance.pas.policy.newbusiness.NewBusinessError;
import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiRequestException extends RuntimeException {
  private final HttpStatus status;
  private final List<NewBusinessError> errors;

  public ApiRequestException(HttpStatus status, String detail) {
    this(status, detail, List.of());
  }

  public ApiRequestException(HttpStatus status, String detail, List<NewBusinessError> errors) {
    super(detail);
    this.status = status;
    this.errors = List.copyOf(errors);
  }

  public HttpStatus status() {
    return status;
  }

  public List<NewBusinessError> errors() {
    return errors;
  }
}
