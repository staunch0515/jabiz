package com.jabiz.entity;

/** A server-side rule check that may depend on runtime services such as the clock. */
@FunctionalInterface
public interface RulePredicate {
    boolean test(Object value, ValidationContext ctx);
}
