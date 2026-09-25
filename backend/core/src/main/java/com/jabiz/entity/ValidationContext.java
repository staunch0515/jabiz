package com.jabiz.entity;

import com.jabiz.context.RequestContext;

import java.time.Clock;
import java.util.Objects;

/**
 * Runtime services made available to rule predicates during evaluation: the business clock and the
 * context of the request being validated (actor, tenant, locale).
 */
public record ValidationContext(Clock clock, RequestContext request) {
    public ValidationContext {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(request, "request must not be null");
    }
}
