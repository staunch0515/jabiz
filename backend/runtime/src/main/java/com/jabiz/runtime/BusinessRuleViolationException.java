package com.jabiz.runtime;

import com.jabiz.entity.Violation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A change was rejected by domain rules: read-only dataset, immutable field, illegal state transition,
 * spatial guard, or an out-of-scope write. All rules broken by one change are reported together
 * (docs/design/02-metamodel.md section 3.1); the response status is 422.
 */
public class BusinessRuleViolationException extends RuntimeException {

    private final List<Violation> violations;

    public BusinessRuleViolationException(List<Violation> violations) {
        super(summarize(violations));
        this.violations = List.copyOf(violations);
    }

    public BusinessRuleViolationException(Violation violation) {
        this(List.of(violation));
    }

    public List<Violation> violations() {
        return violations;
    }

    private static String summarize(List<Violation> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("At least one violation is required");
        }
        return violations.stream().map(Violation::message).collect(Collectors.joining("; "));
    }
}
