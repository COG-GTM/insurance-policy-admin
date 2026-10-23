package com.acme.insurance.pas.policy.inquiry;

import java.util.List;

public record PolicyPageResponse(
    List<PolicyResponse> items, int page, int size, long totalElements, long totalPages) {
  public PolicyPageResponse {
    items = List.copyOf(items);
  }
}
