package com.acme.insurance.pas.parity.stubs;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.fasterxml.jackson.databind.JsonNode;

abstract class AbstractDisabledParityTest implements ParityAdapter<JsonNode, JsonNode> {
  @Override
  public Class<JsonNode> inputType() {
    return JsonNode.class;
  }

  @Override
  public JsonNode run(JsonNode input) {
    throw new UnsupportedOperationException("Parity adapter is not implemented");
  }
}
