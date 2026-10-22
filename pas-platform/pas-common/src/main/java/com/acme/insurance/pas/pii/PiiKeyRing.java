package com.acme.insurance.pas.pii;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class PiiKeyRing {
  private final PiiKey active;
  private final Map<KeyIdentity, PiiKey> keys;

  public PiiKeyRing(PiiKey active, Collection<PiiKey> others) {
    this.active = Objects.requireNonNull(active, "Active PII key is required");
    Map<KeyIdentity, PiiKey> indexed = new HashMap<>();
    indexed.put(identity(active), active);
    if (others != null) {
      for (PiiKey key : others) {
        if (indexed.putIfAbsent(identity(key), key) != null) {
          throw new IllegalArgumentException("Duplicate PII key identifier and version");
        }
      }
    }
    this.keys = Map.copyOf(indexed);
  }

  public PiiKey active() {
    return active;
  }

  public PiiKey find(String keyId, int version) {
    return keys.get(new KeyIdentity(keyId, version));
  }

  public Collection<PiiKey> keys() {
    return keys.values();
  }

  private static KeyIdentity identity(PiiKey key) {
    return new KeyIdentity(key.keyId(), key.version());
  }

  private record KeyIdentity(String keyId, int version) {}
}
