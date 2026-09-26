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
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Sensitive {}
