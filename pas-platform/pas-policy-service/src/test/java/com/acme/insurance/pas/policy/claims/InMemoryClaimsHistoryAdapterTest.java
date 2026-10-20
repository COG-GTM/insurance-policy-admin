package com.acme.insurance.pas.policy.claims;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.policy.claims.InMemoryClaimsHistoryAdapter.Claim;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The stub adapter satisfies the port contract; the default adapter refuses to answer. */
class InMemoryClaimsHistoryAdapterTest extends ClaimsHistoryPortContract {

  @Override
  protected ClaimsHistoryPort adapterWith(List<Claim> claims) {
    InMemoryClaimsHistoryAdapter adapter = new InMemoryClaimsHistoryAdapter();
    claims.forEach(adapter::add);
    return adapter;
  }

  @Test
  void unavailableAdapterNeverReportsZeroClaims() {
    assertThatThrownBy(() -> new UnavailableClaimsHistoryAdapter().summarize("POL001000000", AS_OF))
        .isInstanceOf(ClaimsHistoryUnavailableException.class);
  }
}
