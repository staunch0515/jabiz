package com.jabiz.query.template;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A SQL template that cannot be compiled or rendered. Templates are checked at startup, so at request time this
 * means a defect of the platform or of a definition that bypassed the registry.
 */
public class SqlTemplateException extends IllegalStateException {

    private final String queryId;
    private final List<TemplateProblem> problems;

    public SqlTemplateException(String queryId, List<TemplateProblem> problems) {
        super("SQL template " + queryId + ": " + problems.stream().map(TemplateProblem::message)
            .collect(Collectors.joining("; ")));
        this.queryId = queryId;
        this.problems = List.copyOf(problems);
    }

    public String queryId() {
        return queryId;
    }

    public List<TemplateProblem> problems() {
        return problems;
    }
}
