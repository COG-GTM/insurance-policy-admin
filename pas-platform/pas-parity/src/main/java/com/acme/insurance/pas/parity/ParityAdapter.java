package com.acme.insurance.pas.parity;

public interface ParityAdapter<I, O> {
  String program();

  default String scope() {
    return "component";
  }

  Class<I> inputType();

  O run(I input);
}
