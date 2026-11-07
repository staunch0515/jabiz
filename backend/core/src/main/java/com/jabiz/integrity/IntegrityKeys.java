package com.jabiz.integrity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The key that signs new blocks and the retired keys that signed older ones (decision D78). Blocks are never signed
 * again: a retired key stays here for as long as blocks it signed are to be verified, which is for good.
 */
public final class IntegrityKeys {

    private final IntegrityKey current;
    private final Map<String, IntegrityKey> byId;

    public IntegrityKeys(IntegrityKey current, List<IntegrityKey> previous) {
        this.current = Objects.requireNonNull(current, "current must not be null");
        Map<String, IntegrityKey> keys = new LinkedHashMap<>();
        keys.put(current.id(), current);
        for (IntegrityKey key : List.copyOf(previous)) {
            if (keys.putIfAbsent(key.id(), key) != null) {
                throw new IllegalArgumentException("The integrity key " + key.id() + " is given twice");
            }
        }
        this.byId = Map.copyOf(keys);
    }

    /** Only the current key. */
    public static IntegrityKeys of(IntegrityKey current) {
        return new IntegrityKeys(current, List.of());
    }

    /** The key new blocks and evidence are signed with. */
    public IntegrityKey current() {
        return current;
    }

    /** The key, current or retired, with this id. */
    public Optional<IntegrityKey> byId(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    /** Ids of the retired keys. */
    public List<String> previousIds() {
        return byId.keySet().stream().filter(id -> !id.equals(current.id())).sorted().toList();
    }

    @Override
    public String toString() {
        return "IntegrityKeys[current=" + current.id() + ", previous=" + previousIds() + "]";
    }
}
