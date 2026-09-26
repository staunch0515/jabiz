package com.jabiz.runtime.query;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.TimeSlice;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.custom.TemplateSource;
import com.jabiz.query.template.OuterQueryCompiler;
import com.jabiz.entity.ValidationException;
import com.jabiz.query.template.SqlTemplateRenderer;
import com.jabiz.query.template.SqlText;
import com.jabiz.query.template.SqlTypeCompatibility;
import com.jabiz.query.template.TemplateChecks;
import com.jabiz.query.template.TemplateValues;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Precompiles every SQL template against the database without running it (docs/design/05-sql-template.md section
 * 6): the template is rendered with its datasets' scopes (sample values for those taken from the request), its
 * {@code :name} parameters become {@code ?}, and the JDBC driver describes the statement. The result columns must be
 * exactly the declared {@code results} (unquoted names), of database types compatible with their semantic kinds
 * ({@link SqlTypeCompatibility}); every parameter must be of a compatible type, a list parameter an array. The outer
 * wrapper the platform adds for paging is described too. Database errors are located in the template file.
 *
 * <p>JDBC is used here and only here on purpose: the check runs at startup and in {@code platformCheck}, never on the
 * request path. The connection settings are Flyway's ({@code spring.flyway.url}, {@code user}, {@code password}, and
 * {@code default-schema} or the first of {@code schemas}). Disable with
 * {@code jabiz.sql-templates.precompile-check.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "jabiz.sql-templates.precompile-check", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class SqlTemplatePrecompileCheck implements PlatformCheck {

    public static final String CATEGORY = SqlTemplateLoader.CATEGORY;

    private final SqlTemplateRegistry templates;
    private final EntityDefinitionRegistry entities;
    private final SqlTemplateRenderer renderer;
    private final Environment environment;

    public SqlTemplatePrecompileCheck(SqlTemplateRegistry templates, EntityDefinitionRegistry entities,
        QueryCompiler compiler, Environment environment) {
        this.templates = templates;
        this.entities = entities;
        this.renderer = new SqlTemplateRenderer(compiler);
        this.environment = environment;
    }

    @Override
    public List<CheckProblem> check() {
        List<AdvancedQueryDefinition> queries = templates.allForChecks();
        if (queries.isEmpty()) {
            return List.of();
        }
        String url = environment.getProperty("spring.flyway.url");
        if (url == null || url.isBlank()) {
            return List.of(CheckProblem.error(CATEGORY, "-", "SQL templates cannot be precompiled: spring.flyway.url "
                + "is not set (or disable jabiz.sql-templates.precompile-check.enabled)"));
        }
        List<CheckProblem> problems = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, environment.getProperty("spring.flyway.user"),
            environment.getProperty("spring.flyway.password"))) {
            schema().ifPresent(schema -> {
                try {
                    connection.setSchema(schema);
                } catch (SQLException e) {
                    throw new IllegalStateException("Cannot select schema " + schema, e);
                }
            });
            connection.setReadOnly(true);
            for (AdvancedQueryDefinition query : queries) {
                try {
                    check(connection, query, problems);
                } catch (RuntimeException e) {
                    problems.add(CheckProblem.error(CATEGORY, query.source().path(),
                        "cannot be precompiled: " + e.getMessage()));
                }
            }
        } catch (SQLException e) {
            problems.add(CheckProblem.error(CATEGORY, "-", "SQL templates cannot be precompiled: " + e.getMessage()));
        }
        return problems;
    }

    private Optional<String> schema() {
        String schema = environment.getProperty("spring.flyway.default-schema");
        if (schema == null || schema.isBlank()) {
            String schemas = environment.getProperty("spring.flyway.schemas");
            schema = schemas == null ? null : schemas.split(",")[0].strip();
        }
        return schema == null || schema.isBlank() ? Optional.empty() : Optional.of(schema);
    }

    private void check(Connection connection, AdvancedQueryDefinition query, List<CheckProblem> problems) {
        TemplateSource source = query.source();
        SqlTemplateRenderer.Rendered rendered;
        String outer;
        try {
            Map<String, DatasetDefinition> datasets = templates.datasetsOf(query);
            Map<String, SqlTemplateRenderer.EntityBinding> bindings = new LinkedHashMap<>();
            datasets.forEach((entity, dataset) -> {
                EntityDefinition def = entities.getOrThrow(entity);
                bindings.put(entity, new SqlTemplateRenderer.EntityBinding(def, dataset, sampleScope(def, dataset)));
            });
            rendered = renderer.render(query, bindings, TimeSlice.asOf(Instant.EPOCH),
                new QueryCompiler.Binder(TemplateChecks.SCOPE_PREFIX));
        } catch (IllegalStateException | IllegalArgumentException | ValidationException e) {
            return; // the registry reports templates that cannot be rendered
        }
        outer = OuterQueryCompiler.compile(query, rendered.sql(), null, List.of(), 0, 1).listSql();

        Positional positional = Positional.of(rendered.sql());
        try (PreparedStatement statement = connection.prepareStatement(positional.sql())) {
            ResultSetMetaData columns = statement.getMetaData();
            if (columns == null) {
                problems.add(CheckProblem.error(CATEGORY, source.path(), "the template does not return rows"));
                return;
            }
            checkResults(query, columns, problems);
            checkParameters(query, rendered, positional, statement.getParameterMetaData(), problems);
        } catch (SQLException e) {
            problems.add(CheckProblem.error(CATEGORY, locate(query, rendered, positional, e), message(e)));
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(Positional.of(outer).sql())) {
            statement.getMetaData();
        } catch (SQLException e) {
            problems.add(CheckProblem.error(CATEGORY, source.path(), "the paging query around the template fails: "
                + message(e)));
        }
    }

    private void checkResults(AdvancedQueryDefinition query, ResultSetMetaData columns, List<CheckProblem> problems)
        throws SQLException {
        String location = query.source().path();
        Map<String, Integer> byLabel = new LinkedHashMap<>();
        for (int i = 1; i <= columns.getColumnCount(); i++) {
            String label = columns.getColumnLabel(i);
            if (byLabel.putIfAbsent(label, i) != null) {
                problems.add(CheckProblem.error(CATEGORY, location, "column " + label + " is returned twice"));
            }
        }
        for (ProjectedField field : query.resultFields()) {
            String expected = field.name().toLowerCase(Locale.ROOT);
            Integer index = byLabel.remove(expected);
            if (index == null) {
                Optional<String> quoted = byLabel.keySet().stream().filter(l -> l.equalsIgnoreCase(expected))
                    .findFirst();
                if (quoted.isPresent()) {
                    problems.add(CheckProblem.error(CATEGORY, location, "result " + field.name()
                        + " is returned as the quoted name \"" + quoted.get() + "\"; write the alias unquoted"));
                    byLabel.remove(quoted.get());
                } else {
                    problems.add(CheckProblem.error(CATEGORY, location, "result " + field.name()
                        + " is declared but the query does not return it"));
                }
                continue;
            }
            if (field.kind() == null) {
                continue;
            }
            String type = columns.getColumnTypeName(index);
            SqlTypeCompatibility.checkResult(FieldValueCoercer.javaType(field.kind()), type).ifPresent(mismatch ->
                problems.add(CheckProblem.error(CATEGORY, location, "result " + field.name() + " (" + kindName(field)
                    + "): " + mismatch)));
        }
        for (String extra : byLabel.keySet()) {
            problems.add(CheckProblem.error(CATEGORY, location, "column " + extra
                + " is returned but not declared in results"));
        }
    }

    private void checkParameters(AdvancedQueryDefinition query, SqlTemplateRenderer.Rendered rendered,
        Positional positional, ParameterMetaData metadata, List<CheckProblem> problems) throws SQLException {
        Map<String, QueryParameter> declared = new HashMap<>();
        query.parameters().forEach(p -> declared.put(p.name(), p));
        if (metadata.getParameterCount() != positional.names().size()) {
            problems.add(CheckProblem.error(CATEGORY, query.source().path(), "the database sees "
                + metadata.getParameterCount() + " parameters, the template " + positional.names().size()));
            return;
        }
        for (int i = 0; i < positional.names().size(); i++) {
            QueryParameter parameter = declared.get(positional.names().get(i));
            if (parameter == null || parameter.kind() == null) {
                continue; // platform parameters (scope, time) are generated to fit; undeclared ones are reported
            }
            String type = metadata.getParameterTypeName(i + 1);
            Class<?> javaType = TemplateValues.bindingType(parameter, entities::find);
            int offset = rendered.templateOffset(positional.renderedOffsets().get(i));
            SqlTypeCompatibility.checkParameter(javaType, parameter.list(), type).ifPresent(mismatch ->
                problems.add(CheckProblem.error(CATEGORY, query.source().locate(query.sqlTemplate(), offset),
                    "parameter :" + parameter.name() + " (" + javaType.getSimpleName() + (parameter.list() ? " list" : "")
                        + ") " + mismatch)));
        }
    }

    /** The template line of the error position the server reports, else the file. */
    private static String locate(AdvancedQueryDefinition query, SqlTemplateRenderer.Rendered rendered,
        Positional positional, SQLException e) {
        int position = errorPosition(e);
        if (position <= 0) {
            return query.source().path();
        }
        int renderedOffset = positional.renderedOffsetOfServer(position - 1);
        return query.source().locate(query.sqlTemplate(), rendered.templateOffset(renderedOffset));
    }

    private static int errorPosition(SQLException e) {
        if (e instanceof org.postgresql.util.PSQLException pg && pg.getServerErrorMessage() != null) {
            return pg.getServerErrorMessage().getPosition();
        }
        return 0;
    }

    private static String message(SQLException e) {
        if (e instanceof org.postgresql.util.PSQLException pg && pg.getServerErrorMessage() != null) {
            return "database: " + pg.getServerErrorMessage().getMessage();
        }
        return "database: " + e.getMessage();
    }

    private static String kindName(ProjectedField field) {
        return field.kind().getClass().getSimpleName();
    }

    /** Values a scope could take, to render the scope condition; they are never bound, only described. */
    private static Map<String, Object> sampleScope(EntityDefinition def, DatasetDefinition dataset) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (DatasetScope.Entry entry : dataset.scope().entries()) {
            switch (entry) {
                case DatasetScope.Fixed fixed -> values.put(fixed.field(), fixed.value());
                case DatasetScope.FromContext dynamic -> values.put(dynamic.field(), sample(def.field(dynamic.field())));
            }
        }
        return values;
    }

    private static Object sample(FieldDefinition field) {
        Class<?> type = FieldValueCoercer.javaType(field.kind());
        if (type == BigDecimal.class) return BigDecimal.ZERO;
        if (type == Long.class) return 0L;
        if (type == Instant.class) return Instant.EPOCH;
        if (type == Boolean.class) return Boolean.FALSE;
        if (type == UUID.class) return new UUID(0, 0);
        return "0";
    }

    /**
     * The statement for JDBC: its {@code :name} parameters replaced by {@code ?} and literal question marks of SQL
     * operators (jsonb {@code ?}, {@code ?|}, {@code ?&}) escaped as {@code ??}; the name of each {@code ?} and where it
     * was in the rendered SQL. The driver sends each {@code ?} as {@code $n}, and the server reports error positions in
     * that text, which {@link #renderedOffsetOfServer} maps back.
     */
    record Positional(String sql, List<String> names, List<Integer> renderedOffsets) {

        static Positional of(String rendered) {
            String masked = SqlText.mask(rendered);
            StringBuilder sql = new StringBuilder();
            List<String> names = new ArrayList<>();
            List<Integer> offsets = new ArrayList<>();
            int copied = 0;
            for (SqlText.ParameterRef ref : SqlText.parameters(masked)) {
                appendEscaped(sql, rendered, masked, copied, ref.start());
                sql.append('?');
                names.add(ref.name());
                offsets.add(ref.start());
                copied = ref.end();
            }
            appendEscaped(sql, rendered, masked, copied, rendered.length());
            return new Positional(sql.toString(), names, offsets);
        }

        private static void appendEscaped(StringBuilder out, String rendered, String masked, int from, int to) {
            for (int i = from; i < to; i++) {
                char c = rendered.charAt(i);
                out.append(c == '?' && masked.charAt(i) == '?' ? "??" : String.valueOf(c));
            }
        }

        /** Offset in the rendered SQL of an offset in the text the server received. */
        int renderedOffsetOfServer(int serverOffset) {
            int delta = 0; // rendered length minus server length of the parameters before
            for (int i = 0; i < names.size(); i++) {
                int serverStart = renderedOffsets.get(i) - delta;
                if (serverOffset <= serverStart) {
                    break;
                }
                int renderedLength = names.get(i).length() + 1;
                int serverLength = 1 + String.valueOf(i + 1).length();
                delta += renderedLength - serverLength;
            }
            return serverOffset + delta;
        }
    }
}
