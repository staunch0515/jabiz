package com.jabiz.runtime.param;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.param.ParamKinds;
import com.jabiz.runtime.EntityInstance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The business parameter part of controlled changes (decision D40, docs/design/18-numbering-approvals-tasks.md
 * section 3.5): a change names the parameter by {@code paramKey} and sets its {@code value} (and
 * {@code description}); a change that creates the parameter gives its {@code valueKind} as well. A deletion cancels
 * the value scheduled at the change's effective time. Only controlled keys take this way; their values are checked
 * against the parameter's kind when the change is proposed and again when it is published.
 */
public final class ParamControlChanges {

    /** The fields a change of a parameter may give. */
    public static final Set<String> WRITABLE = Set.of(ParamEntities.KEY, ParamEntities.KIND, ParamEntities.VALUE,
        ParamEntities.DESCRIPTION);

    /**
     * @param violations why the change cannot be made; empty when it can
     * @param values     the values to store with the change: the key, the canonical value and the other given fields
     */
    public record Prepared(List<Violation> violations, Map<String, Object> values) {}

    private ParamControlChanges() {}

    /** The key a change names, or null. */
    public static String key(Map<String, ?> values) {
        return values == null || !(values.get(ParamEntities.KEY) instanceof String key) ? null : key;
    }

    /**
     * Checks a change of the parameter {@code target} (null: it does not exist yet, as of the change's effective
     * time) and puts its values in the form they are stored.
     *
     * @param delete    true to cancel the value scheduled at {@code effective}
     * @param effective when the change takes effect; null: when it is published
     * @param now       the time of the operation
     */
    public static Prepared prepare(Map<String, Object> values, EntityInstance target, boolean delete,
        Instant effective, Instant now, ControlledParamRegistry controlled) {
        List<Violation> violations = new ArrayList<>();
        Map<String, Object> given = values == null ? Map.of() : values;
        Map<String, Object> stored = new LinkedHashMap<>();
        new TreeSet<>(given.keySet()).stream().filter(field -> !WRITABLE.contains(field))
            .forEach(field -> violations.add(invalid("field '" + field + "' cannot be set")));
        String key = key(given);
        if (key == null) {
            violations.add(invalid("the parameter is named by values.paramKey"));
            return new Prepared(violations, stored);
        }
        stored.put(ParamEntities.KEY, key);
        if (!controlled.controls(key)) {
            violations.add(invalid("parameter " + key + " is not controlled; it is changed by PARAM_SET or"
                + " PARAM_SCHEDULE"));
        }
        if (effective != null && !effective.isAfter(now)) {
            violations.add(new Violation("effectiveTime", PlatformErrorCodes.EFFECTIVE_TIME_NOT_FUTURE,
                "The effective time " + effective + " is not later than now", Map.of("time", effective.toString())));
        }
        if (delete) {
            if (effective == null) {
                violations.add(invalid("a deletion cancels the value scheduled at its effectiveTime; give it"));
            }
            if (given.size() > 1) {
                violations.add(invalid("a deletion gives no values but paramKey"));
            }
            if (target == null) {
                violations.add(invalid("parameter " + key + " does not exist"));
            }
            return new Prepared(violations, stored);
        }
        SemanticKind kind = null;
        Object kindSpec;
        if (target == null) {
            kindSpec = given.get(ParamEntities.KIND);
            if (!(kindSpec instanceof Map<?, ?>)) {
                violations.add(invalid("parameter " + key + " does not exist; a change that creates it gives its"
                    + " valueKind"));
            } else {
                kind = kind(kindSpec, violations);
                stored.put(ParamEntities.KIND, kindSpec);
            }
            if (!given.containsKey(ParamEntities.VALUE)) {
                violations.add(invalid("a change that creates parameter " + key + " gives its value"));
            }
        } else {
            kindSpec = target.get(ParamEntities.KIND);
            kind = kind(kindSpec, violations);
            if (given.containsKey(ParamEntities.KIND) && !sameKind(given.get(ParamEntities.KIND), kindSpec)) {
                violations.add(invalid("the valueKind of parameter " + key + " cannot change"));
            }
            if (!given.containsKey(ParamEntities.VALUE) && !given.containsKey(ParamEntities.DESCRIPTION)) {
                violations.add(invalid("no values"));
            }
        }
        if (given.containsKey(ParamEntities.VALUE)) {
            Object raw = given.get(ParamEntities.VALUE);
            if (raw == null) {
                violations.add(invalid("the value of parameter " + key + " cannot be empty"));
            } else if (kind != null) {
                String type = String.valueOf(((Map<?, ?>) kindSpec).get("type"));
                try {
                    stored.put(ParamEntities.VALUE, ParamKinds.canonical(kind, raw));
                } catch (IllegalArgumentException e) {
                    violations.add(new Violation("values.value", PlatformErrorCodes.PARAM_VALUE_INVALID,
                        "The value is not a " + type + ": " + e.getMessage(), Map.of("kind", type)));
                }
            }
        }
        if (given.containsKey(ParamEntities.DESCRIPTION)) {
            stored.put(ParamEntities.DESCRIPTION, given.get(ParamEntities.DESCRIPTION));
        }
        return new Prepared(violations, stored);
    }

    /** The fields a published change writes: all of them for a new parameter, else the value and description. */
    public static Map<String, Object> writes(Map<String, Object> stored, boolean create) {
        Map<String, Object> writes = new LinkedHashMap<>(stored);
        if (!create) {
            writes.remove(ParamEntities.KEY);
            writes.remove(ParamEntities.KIND);
        }
        return writes;
    }

    @SuppressWarnings("unchecked")
    private static SemanticKind kind(Object spec, List<Violation> violations) {
        try {
            return ParamKinds.parse((Map<String, ?>) spec);
        } catch (IllegalArgumentException | ClassCastException e) {
            violations.add(invalid("valueKind: " + e.getMessage()));
            return null;
        }
    }

    private static boolean sameKind(Object given, Object stored) {
        if (!(given instanceof Map<?, ?> a) || !(stored instanceof Map<?, ?> b) || a.size() != b.size()) {
            return false;
        }
        return a.entrySet().stream().allMatch(entry -> b.containsKey(String.valueOf(entry.getKey()))
            && Objects.equals(String.valueOf(entry.getValue()), String.valueOf(b.get(String.valueOf(entry.getKey())))));
    }

    private static Violation invalid(String detail) {
        return new Violation("values", PlatformErrorCodes.CONTROL_CHANGE_INVALID, "Invalid change: " + detail,
            Map.of("detail", detail));
    }
}
