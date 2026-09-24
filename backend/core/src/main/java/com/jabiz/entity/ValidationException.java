package com.jabiz.entity;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when input attributes fail validation against an entity definition. */
public class ValidationException extends RuntimeException {

    private final List<Violation> violations;

    public ValidationException(List<Violation> violations) {
        super(summarize(violations));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    private static String summarize(List<Violation> violations) {
        return violations.stream()
            .map(v -> v.field() + ": " + v.ruleCode())
            .collect(Collectors.joining(", ", "Validation failed [", "]"));
    }
}
