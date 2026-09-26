package com.jabiz.runtime.check;

import java.util.Objects;

/**
 * One finding of a platform check (docs/design/07-quality.md sections 1 and 2), printed as
 * {@code category | location | description}.
 *
 * @param location where the problem is: a file and line ({@code queries/x.sql:12}), an entity or field, a dataset
 */
public record CheckProblem(Severity severity, String category, String location, String message) {

    public enum Severity { ERROR, WARNING }

    public CheckProblem {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(category, "category must not be null");
        location = location == null || location.isBlank() ? "-" : location;
        Objects.requireNonNull(message, "message must not be null");
    }

    public static CheckProblem error(String category, String location, String message) {
        return new CheckProblem(Severity.ERROR, category, location, message);
    }

    public static CheckProblem warning(String category, String location, String message) {
        return new CheckProblem(Severity.WARNING, category, location, message);
    }

    /**
     * A problem written as {@code "location: message"} or {@code "location -> message"}, whichever separator comes
     * first; without either the whole text is the message.
     */
    public static CheckProblem error(String category, String text) {
        int colon = text.indexOf(": ");
        int arrow = text.indexOf(" -> ");
        if (colon < 0 && arrow < 0) {
            return error(category, "-", text);
        }
        return colon >= 0 && (arrow < 0 || colon < arrow)
            ? error(category, text.substring(0, colon), text.substring(colon + 2))
            : error(category, text.substring(0, arrow), text.substring(arrow + 4));
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public String format() {
        return (isError() ? "" : "WARNING ") + category + " | " + location + " | " + message;
    }
}
