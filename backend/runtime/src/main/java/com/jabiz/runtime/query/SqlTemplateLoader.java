package com.jabiz.runtime.query;

import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.template.SqlTemplateException;
import com.jabiz.query.template.SqlTemplateFile;
import com.jabiz.query.template.TemplateProblem;
import com.jabiz.runtime.check.CheckProblem;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the {@code .sql} templates (docs/design/05-sql-template.md sections 1 and 2) from
 * {@code jabiz.sql-templates.locations} (comma separated resource patterns, default
 * {@value #DEFAULT_LOCATIONS}): splits off the header, parses it as YAML, validates it against
 * {@code jabiz/schema/sql-template-header.schema.json} and compiles it to an {@link AdvancedQueryDefinition}.
 * Problems of every file are collected, not thrown.
 */
@Component
public class SqlTemplateLoader {

    public static final String LOCATIONS_PROPERTY = "jabiz.sql-templates.locations";
    public static final String DEFAULT_LOCATIONS = "classpath*:queries/**/*.sql";
    public static final String CATEGORY = "SQL_TEMPLATE";
    public static final String SCHEMA = "jabiz/schema/sql-template-header.schema.json";

    /** The templates that compiled, and the problems of those that did not. */
    /**
     * @param sources the text of each template file by its version (docs/design/19-reports.md section 2.3), which
     *                issued reports keep
     */
    public record Loaded(List<AdvancedQueryDefinition> queries, List<CheckProblem> problems,
        Map<String, String> sources) {}

    private final List<String> locations;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final YAMLMapper yaml = YAMLMapper.builder().build();
    private final Schema schema;

    public SqlTemplateLoader(Environment environment) {
        this.locations = Arrays.stream(environment.getProperty(LOCATIONS_PROPERTY, DEFAULT_LOCATIONS).split(","))
            .map(String::strip).filter(s -> !s.isEmpty()).toList();
        try (InputStream in = SqlTemplateLoader.class.getClassLoader().getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + SCHEMA);
            }
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Loaded load() {
        List<AdvancedQueryDefinition> queries = new ArrayList<>();
        List<CheckProblem> problems = new ArrayList<>();
        Map<String, String> sources = new HashMap<>();
        for (String location : locations) {
            for (Resource resource : resources(location)) {
                String path = relativePath(location, resource);
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8);
                    int before = queries.size();
                    load(path, content, queries, problems);
                    if (queries.size() > before) {
                        sources.put(queries.getLast().version(), content);
                    }
                } catch (IOException e) {
                    problems.add(CheckProblem.error(CATEGORY, path, "cannot be read: " + e.getMessage()));
                }
            }
        }
        return new Loaded(queries, problems, Map.copyOf(sources));
    }

    /** Compiles one file; its problems are added to {@code problems}. */
    void load(String path, String content, List<AdvancedQueryDefinition> queries, List<CheckProblem> problems) {
        SqlTemplateFile.Parts parts;
        try {
            parts = SqlTemplateFile.split(path, content);
        } catch (SqlTemplateException e) {
            e.problems().forEach(p -> problems.add(CheckProblem.error(CATEGORY, path + ":1", p.message())));
            return;
        }
        String headerLocation = path + ":" + parts.headerLine();
        JsonNode header;
        try {
            header = yaml.readTree(parts.header());
        } catch (JacksonException e) {
            int line = e.getLocation() == null ? 0 : Math.max(0, e.getLocation().getLineNr() - 1);
            problems.add(CheckProblem.error(CATEGORY, path + ":" + (parts.headerLine() + line),
                "header is not valid YAML: " + e.getOriginalMessage()));
            return;
        }
        if (header == null || !header.isObject()) {
            problems.add(CheckProblem.error(CATEGORY, headerLocation, "header must be a YAML mapping"));
            return;
        }
        List<Error> errors = schema.validate(header);
        if (!errors.isEmpty()) {
            errors.stream().sorted(Comparator.comparing(error -> error.getInstanceLocation().toString()))
                .forEach(error -> problems.add(CheckProblem.error(CATEGORY, headerLocation,
                    "header " + error.getInstanceLocation() + ": " + error.getMessage())));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = yaml.treeToValue(header, Map.class);
        try {
            queries.add(SqlTemplateFile.compile(path, map, parts));
        } catch (SqlTemplateException e) {
            for (TemplateProblem problem : e.problems()) {
                problems.add(CheckProblem.error(CATEGORY, headerLocation, problem.message()));
            }
        }
    }

    private Resource[] resources(String location) {
        try {
            Resource[] found = resolver.getResources(location);
            Arrays.sort(found, Comparator.comparing(r -> r.getDescription()));
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list SQL templates at " + location, e);
        }
    }

    /**
     * The resource's path from the root directory of the pattern (for {@code classpath*:queries/**}{@code /*.sql}
     * the path starts with {@code queries/}), so problem reports name the file as it appears in the sources.
     */
    static String relativePath(String location, Resource resource) {
        String pattern = location.replaceFirst("^classpath\\*?:", "").replaceFirst("^/", "");
        int wildcard = pattern.indexOf('*');
        String root = wildcard < 0 ? pattern : pattern.substring(0, pattern.lastIndexOf('/', wildcard) + 1);
        String url;
        try {
            url = resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
        }
        int at = root.isEmpty() ? -1 : url.lastIndexOf(root);
        return at < 0 ? resource.getFilename() : url.substring(at);
    }
}
