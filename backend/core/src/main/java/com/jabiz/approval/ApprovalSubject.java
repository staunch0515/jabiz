package com.jabiz.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Something that may need approval, declared in code as a bean (docs/design/18-numbering-approvals-tasks.md
 * section 3.1): a journal entry, a payment, a supplier certification. It names the facts the approval rules may test;
 * a rule that tests anything else is refused when it is proposed.
 *
 * <pre>{@code
 * ApprovalSubject.define("fin.journal", s -> s.number("amount").text("source").bool("manual"))
 * }</pre>
 *
 * @param name  1–100 lower-case letters, digits and {@code . _ -}, starting with a letter; unique
 * @param facts the facts by name, in declaration order
 */
public record ApprovalSubject(String name, Map<String, FactType> facts) {

    /** Names of subjects. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9._-]{0,99}");

    /** Names of facts. */
    public static final Pattern FACT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    public ApprovalSubject {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Approval subject name '" + name + "' must match " + NAME.pattern());
        }
        Objects.requireNonNull(facts, "facts must not be null");
        facts.forEach((fact, type) -> {
            if (fact == null || !FACT.matcher(fact).matches()) {
                throw new IllegalArgumentException("Fact '" + fact + "' of approval subject " + name + " must match "
                    + FACT.pattern());
            }
            Objects.requireNonNull(type, "type of fact " + fact + " must not be null");
        });
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    public static ApprovalSubject define(String name, Consumer<Builder> spec) {
        Builder builder = new Builder();
        spec.accept(builder);
        return new ApprovalSubject(name, builder.facts);
    }

    /**
     * The facts in canonical form (numbers as {@link java.math.BigDecimal}). Fails listing every fact that is not
     * declared or not of its declared type; a declared fact may be missing or null (conditions on it are false).
     */
    public Map<String, Object> normalizeFacts(Map<String, ?> given) {
        Objects.requireNonNull(given, "facts must not be null");
        List<String> problems = new ArrayList<>();
        Map<String, Object> normalized = new LinkedHashMap<>();
        given.forEach((fact, value) -> {
            FactType type = facts.get(fact);
            if (type == null) {
                problems.add("fact '" + fact + "' is not declared by approval subject " + name);
            } else if (value != null) {
                Object canonical = type.normalize(value);
                if (canonical == null) {
                    problems.add("fact '" + fact + "' of approval subject " + name + " must be " + type + ", not "
                        + value.getClass().getSimpleName());
                } else {
                    normalized.put(fact, canonical);
                }
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return Collections.unmodifiableMap(normalized);
    }

    public static final class Builder {
        private final Map<String, FactType> facts = new LinkedHashMap<>();

        private Builder() {}

        public Builder fact(String name, FactType type) {
            if (facts.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException("Fact " + name + " is declared twice");
            }
            return this;
        }

        public Builder number(String name) {
            return fact(name, FactType.NUMBER);
        }

        public Builder text(String name) {
            return fact(name, FactType.TEXT);
        }

        public Builder bool(String name) {
            return fact(name, FactType.BOOLEAN);
        }
    }
}
