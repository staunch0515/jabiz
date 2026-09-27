package com.jabiz.query.template;

import com.jabiz.entity.SemanticKindParser;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.custom.ResultListSpec;
import com.jabiz.query.custom.TemplateSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@code .sql} template file (docs/design/05-sql-template.md section 2): a header in a {@code /*--- ... ---*}{@code /}
 * comment, then the SQL. {@link #split} separates the two; the header is parsed from YAML by the runtime and turned
 * into the same {@link AdvancedQueryDefinition} the Java DSL builds by {@link #compile}.
 */
public final class SqlTemplateFile {

    public static final String HEADER_START = "/*---";
    public static final String HEADER_END = "---*/";

    /**
     * @param headerLine line on which the header text starts
     * @param bodyLine   line on which the SQL starts
     */
    public record Parts(String header, int headerLine, String body, int bodyLine) {}

    private SqlTemplateFile() {}

    /** @throws SqlTemplateException if the file does not start with a header */
    public static Parts split(String path, String content) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (!text.startsWith(HEADER_START, start)) {
            throw new SqlTemplateException(path, List.of(TemplateProblem.header(
                "file must start with a header comment " + HEADER_START + " ... " + HEADER_END)));
        }
        int headerStart = start + HEADER_START.length();
        int end = text.indexOf(HEADER_END, headerStart);
        if (end < 0) {
            throw new SqlTemplateException(path, List.of(TemplateProblem.header("header is not closed by "
                + HEADER_END)));
        }
        int bodyStart = end + HEADER_END.length();
        return new Parts(text.substring(headerStart, end), lineOf(text, headerStart), text.substring(bodyStart),
            lineOf(text, bodyStart));
    }

    /**
     * Builds the definition from the parsed header. The header is assumed to have passed the JSON Schema; anything it
     * cannot express (kind parameters, references) is reported here.
     *
     * @throws SqlTemplateException listing every problem of the header
     */
    public static AdvancedQueryDefinition compile(String path, Map<String, Object> header, Parts parts) {
        List<TemplateProblem> problems = new ArrayList<>();
        String id = string(header.get("id"));
        if (id == null || id.isBlank()) {
            throw new SqlTemplateException(path, List.of(TemplateProblem.header("header needs an id")));
        }
        AdvancedQueryDefinition.Builder builder = new AdvancedQueryDefinition.Builder(id)
            .source(new TemplateSource(path, parts.bodyLine()))
            .sqlTemplate(parts.body());
        if (header.get("description") != null) {
            builder.description(string(header.get("description")));
        }
        for (Object entity : list(header.get("entities"))) {
            builder.fromEntities(string(entity));
        }
        map(header.get("datasets")).forEach((entity, dataset) -> builder.dataset(entity, string(dataset)));
        map(header.get("params")).forEach((name, spec) -> parameter(builder, name, map(spec), problems));
        map(header.get("results")).forEach((name, spec) -> result(builder, name, map(spec), problems));
        Map<String, Object> list = map(header.get("list"));
        if (!list.isEmpty()) {
            builder.list(l -> {
                list(list.get("filters")).forEach(f -> l.filters(string(f)));
                list(list.get("sorts")).forEach(s -> l.sorts(string(s)));
                list(list.get("key")).forEach(k -> l.key(string(k)));
                Map<String, Object> sort = map(list.get("defaultSort"));
                if (!sort.isEmpty()) {
                    l.defaultSort(string(sort.get("field")), !Boolean.FALSE.equals(sort.get("asc")));
                }
            });
        }
        builder.permissions(list(header.get("permissions")).stream().map(SqlTemplateFile::string)
            .toArray(String[]::new));
        Object access = header.get("access");
        if ("public".equals(access)) {
            builder.publicAccess();
        } else if (access != null) {
            problems.add(TemplateProblem.header("access must be public when given, not " + access));
        }
        if (header.get("cacheSeconds") instanceof Number seconds) {
            builder.cacheSeconds(seconds.intValue());
        }
        if (header.get("timeoutMs") instanceof Number ms) {
            builder.timeout(Duration.ofMillis(ms.longValue()));
        }
        if (!problems.isEmpty()) {
            throw new SqlTemplateException(id, problems);
        }
        try {
            return builder.build();
        } catch (IllegalStateException e) {
            throw new SqlTemplateException(id, List.of(TemplateProblem.header(e.getMessage())));
        }
    }

    private static void parameter(AdvancedQueryDefinition.Builder builder, String name, Map<String, Object> spec,
        List<TemplateProblem> problems) {
        boolean required = Boolean.TRUE.equals(spec.get("required"));
        boolean isList = Boolean.TRUE.equals(spec.get("list"));
        Object defaultValue = spec.get("default");
        String like = string(spec.get("like"));
        try {
            QueryParameter parameter;
            if (like != null) {
                String[] ref = reference(like);
                parameter = QueryParameter.like(name, ref[0], ref[1], required, isList);
            } else {
                parameter = new QueryParameter(name, SemanticKindParser.parse(map(spec.get("kind"))), required, null,
                    "", isList, null, null);
            }
            if (defaultValue != null || spec.get("description") != null) {
                parameter = new QueryParameter(name, parameter.kind(), required, defaultValue,
                    spec.get("description") == null ? "" : string(spec.get("description")), isList,
                    parameter.likeEntity(), parameter.likeField());
            }
            builder.parameter(parameter);
        } catch (IllegalArgumentException e) {
            problems.add(TemplateProblem.header("parameter " + name + ": " + e.getMessage()));
        }
    }

    private static void result(AdvancedQueryDefinition.Builder builder, String name, Map<String, Object> spec,
        List<TemplateProblem> problems) {
        try {
            String from = string(spec.get("from"));
            if (from != null) {
                String[] ref = reference(from);
                builder.returns(spec.get("kind") == null
                    ? ProjectedField.inherit(name, ref[0], ref[1])
                    : ProjectedField.from(name, SemanticKindParser.parse(map(spec.get("kind"))), ref[0], ref[1]));
            } else {
                builder.returns(ProjectedField.of(name, SemanticKindParser.parse(map(spec.get("kind")))));
            }
        } catch (IllegalArgumentException e) {
            problems.add(TemplateProblem.header("result " + name + ": " + e.getMessage()));
        }
    }

    private static String[] reference(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("'" + text + "' is not of the form Entity.field");
        }
        return parts;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
