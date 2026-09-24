package com.jabiz.runtime;

/**
 * A change was rejected by a domain rule: read-only dataset, immutable field, illegal state
 * transition, spatial guard, or an out-of-scope write.
 */
public class BusinessRuleViolationException extends RuntimeException {
    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
