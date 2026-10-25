package com.jabiz.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component of a process input or output as a secret (a password, a token): the platform masks it wherever
 * it records or logs the value, for example in {@code op_process.input_summary} (docs/design/10-security.md).
 * The record's own {@code toString()} should mask it too.
 *
 * <p>The masking is by the component's name, wherever that name appears: choose a distinctive one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Sensitive {

    /**
     * Whether the caller of the process is given the value in the response all the same (a one-time secret made for
     * them, a prize code opened by its winner): then the value is masked in every record (the input summary, the
     * output kept for an idempotent replay, the events) but not in the process API's answer. A replay of the same
     * request returns it as null. Default: masked in the answer too.
     */
    boolean returned() default false;
}
