package com.jabiz.query.template;

/**
 * A problem found in a SQL template.
 *
 * @param offset  offset in the SQL template the problem refers to, or -1 when it concerns the header
 * @param warning true for findings that do not stop the application (for example a bare physical name)
 */
public record TemplateProblem(int offset, String message, boolean warning) {

    public static TemplateProblem error(int offset, String message) {
        return new TemplateProblem(offset, message, false);
    }

    public static TemplateProblem header(String message) {
        return new TemplateProblem(-1, message, false);
    }

    public static TemplateProblem warning(int offset, String message) {
        return new TemplateProblem(offset, message, true);
    }
}
