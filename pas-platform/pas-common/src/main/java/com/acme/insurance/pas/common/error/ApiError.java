package com.acme.insurance.pas.common.error;

import java.time.Instant;

/**
 * Uniform error payload returned by PAS platform REST services.
 *
 * @param code stable machine-readable error code
 * @param message human-readable detail
 * @param timestamp when the error was produced (UTC)
 * @param path request path that failed
 * @param traceId correlation/trace identifier
 */
public record ApiError(
    String code, String message, Instant timestamp, String path, String traceId) {

  public ApiError {
    if (timestamp == null) {
      timestamp = Instant.now();
    }
  }

  public static ApiError of(String code, String message, String path, String traceId) {
    return new ApiError(code, message, Instant.now(), path, traceId);
  }
}
