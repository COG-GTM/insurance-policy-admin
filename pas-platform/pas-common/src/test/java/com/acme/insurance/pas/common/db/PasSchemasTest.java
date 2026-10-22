package com.acme.insurance.pas.common.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PasSchemasTest {
  @Test
  void exposesOwnedSchemas() {
    assertEquals("pas", PasSchemas.PAS);
    assertEquals("pas_batch", PasSchemas.PAS_BATCH);
    assertEquals("pas_shadow", PasSchemas.PAS_SHADOW);
  }
}
