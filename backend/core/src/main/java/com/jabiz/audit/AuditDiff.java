package com.jabiz.audit;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a write changed, for the audit trail (docs/design/21-audit-retention.md section 1, decision D27): per field
 * the value before and after, in a canonical form (decimals as plain text with their scale, times as UTC ISO-8601,
 * nested maps with sorted keys), with secrets masked. An insert has no "before", a delete no "after".
 */
public final class AuditDiff {

    /** What a secret's value is recorded as: that it changed is kept, the value never. */
    public static final String MASK = "***";

    /** One field's change; {@code before} or {@code after} is null when the field had or has no value. */
    public record Change(Object before, Object after) {}

    private AuditDiff() {}

    /**
     * The changes between two states of an instance of {@code def}.
     *
     * @param before the state before the write; null for an insert
     * @param after  the state after the write; null for a delete
     * @return field name to change, sorted by field name; fields with the same value on both sides are left out
     */
    public static Map<String, Change> of(EntityDefinition def, Map<String, ?> before, Map<String, ?> after) {
        Objects.requireNonNull(def, "def must not be null");
        Map<String, ?> was = before == null ? Map.of() : before;
        Map<String, ?> is = after == null ? Map.of() : after;
        TreeSet<String> names = new TreeSet<>();
        names.addAll(was.keySet());
        names.addAll(is.keySet());
        Map<String, Change> changes = new TreeMap<>();
        for (String name : names) {
            FieldDefinition field = def.fields.get(name);
            if (field == null) {
                continue;  // not a field of the entity (a system column the caller passed along)
            }
            Object from = canonical(was.get(name));
            Object to = canonical(is.get(name));
            if (Objects.equals(from, to)) {
                continue;
            }
            if (field.sensitive()) {
                changes.put(name, new Change(from == null ? null : MASK, to == null ? null : MASK));
            } else {
                changes.put(name, new Change(from, to));
            }
        }
        return Collections.unmodifiableMap(changes);
    }

    /**
     * A value in the form the trail keeps: decimals as plain text (so {@code 1.50} and {@code 1.5} differ, as the
     * stored values do), times as UTC ISO-8601, maps with sorted keys, collections as lists; texts, booleans and
     * whole numbers as they are; anything else as its text.
     */
    public static Object canonical(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal d -> d.toPlainString();
            case Double d -> BigDecimal.valueOf(d).toPlainString();
            case Float f -> new BigDecimal(f.toString()).toPlainString();
            case Instant i -> i.toString();
            case OffsetDateTime t -> t.toInstant().toString();
            case String s -> s;
            case Boolean b -> b;
            case Integer i -> i.longValue();
            case Long l -> l;
            case Short s -> s.longValue();
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
                yield Collections.unmodifiableMap(sorted);
            }
            case Collection<?> list -> {
                List<Object> copy = new ArrayList<>();
                list.forEach(item -> copy.add(canonical(item)));
                yield Collections.unmodifiableList(copy);
            }
            case Object[] array -> canonical(Arrays.asList(array));
            default -> value.toString();
        };
    }
}
