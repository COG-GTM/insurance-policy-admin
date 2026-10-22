package com.acme.insurance.pas.parity;

public interface ParityAdapter<I, O> {
  String program();

  Class<I> inputType();

  O run(I input);
}
