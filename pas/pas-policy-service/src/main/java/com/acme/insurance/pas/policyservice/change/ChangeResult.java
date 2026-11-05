package com.acme.insurance.pas.policyservice.change;

import com.acme.insurance.pas.rating.PolicyChangeError;

/**
 * Outcome of a renewal or endorsement. {@code NOT_FOUND} and {@code DENIED} both answer 404, so a
 * caller cannot tell a policy outside its entitlement from one that does not exist.
 */
public record ChangeResult<T>(Outcome outcome, T value, PolicyChangeError error) {

  public enum Outcome {
    DONE,
    NOT_FOUND,
    DENIED,
    REJECTED,
    CONFLICT
  }

  static <T> ChangeResult<T> done(T value) {
    return new ChangeResult<>(Outcome.DONE, value, null);
  }

  static <T> ChangeResult<T> notFound() {
    return new ChangeResult<>(Outcome.NOT_FOUND, null, null);
  }

  static <T> ChangeResult<T> denied() {
    return new ChangeResult<>(Outcome.DENIED, null, null);
  }

  static <T> ChangeResult<T> rejected(PolicyChangeError error) {
    return new ChangeResult<>(Outcome.REJECTED, null, error);
  }

  static <T> ChangeResult<T> conflict(PolicyChangeError error) {
    return new ChangeResult<>(Outcome.CONFLICT, null, error);
  }
}
