package com.jabiz.entity;

import java.time.Clock;
import java.util.Objects;

/** Runtime services made available to rule predicates during evaluation. */
public record ValidationContext(Clock clock) {
    public ValidationContext {
        Objects.requireNonNull(clock, "clock must not be null");
    }
}
