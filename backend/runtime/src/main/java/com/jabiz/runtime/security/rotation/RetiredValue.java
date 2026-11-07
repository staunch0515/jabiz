package com.jabiz.runtime.security.rotation;

import com.jabiz.runtime.EntityInstance;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A value stored under a retired key, found by {@link FindRetiredValues} for {@link StoreUnderCurrentKey}. */
record RetiredValue(Target target, EntityInstance row) {

    enum Kind { MFA, SEALED }

    /**
     * Where such values are: the entity, the field, how every value under a retired key begins, and the context
     * (the user of a TOTP secret, the declared context of a sealed value) from the row's fields.
     */
    record Target(Kind kind, String entity, String field, List<String> retiredPrefixes,
        Function<Map<String, Object>, String> context) {

        Target {
            retiredPrefixes = List.copyOf(retiredPrefixes);
        }
    }

    String stored() {
        Object value = row.attributes().get(target.field());
        return value == null ? null : value.toString();
    }

    String context() {
        return target.context().apply(row.attributes());
    }

    @Override
    public String toString() {
        return "RetiredValue[" + target.entity() + "." + target.field() + " of " + row.id() + "]";
    }
}
