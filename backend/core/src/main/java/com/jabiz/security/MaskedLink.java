package com.jabiz.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component of a process input as a link whose query (and user information) the platform keeps out of its
 * records (decision D75): {@code op_process.input_summary} shows {@code https://host/path?…}. Links signed for one
 * download carry their credentials in the query. Like {@link Sensitive}, by the component's name wherever it appears:
 * choose a distinctive one ({@code imageLink}, not {@code url}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface MaskedLink {}
