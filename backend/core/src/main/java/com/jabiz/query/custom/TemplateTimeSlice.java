package com.jabiz.query.custom;

/**
 * Header {@code timeSlice} of a SQL template (docs/design/19-reports.md section 2.2): the parameters whose values are
 * the point in time the template's temporal entities are read at. Either may be null.
 *
 * @param asOf    parameter giving the effective time; its absence (or a null value) means now
 * @param knownAt parameter giving the recorded time; its absence (or a null value) means no limit
 */
public record TemplateTimeSlice(String asOf, String knownAt) {}
