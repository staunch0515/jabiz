package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Runs a registered SQL template and puts its rows, a {@code List<Map<String, Object>>} of result column to value,
 * into the context (docs/design/06-process.md section 2.1). Every entity of the template is read through its
 * dataset with the caller's scope (decision D10). The template's own permissions are not checked: running the
 * process required the process's permissions (decision D11). All its rows, not a page: the datasets'
 * {@code maxQueryBatchSize} limits browsing only; more than {@code jabiz.process.max-read-rows} (100,000 by default)
 * fails the process (422 {@code PROCESS_READ_TOO_LARGE}) rather than giving it part of the result (decision D32).
 */
@Component
public class RunTemplate<C extends ProcessContext> implements StepHandler<RunTemplate.Metadata<C>, C>,
    CheckedStep<RunTemplate.Metadata<C>> {

    /**
     * @param asOf      effective time the template's temporal entities are read at (null: now)
     * @param knownAt   recorded time they are read as of (null: everything recorded so far)
     * @param condition whether to run at all, decided on the context when the step is reached; when it does not
     *                  hold the template is not run (no statement is sent) and {@code targetKey} holds an empty list
     */
    public record Metadata<C>(String templateId, Function<C, Map<String, Object>> params, Function<C, Instant> asOf,
        Function<C, Instant> knownAt, String targetKey, Predicate<C> condition) {
        public Metadata {
            Objects.requireNonNull(templateId, "templateId must not be null");
            Objects.requireNonNull(params, "params must not be null");
            Objects.requireNonNull(asOf, "asOf must not be null");
            Objects.requireNonNull(knownAt, "knownAt must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
            condition = condition == null ? ctx -> true : condition;
        }

        public Metadata(String templateId, Function<C, Map<String, Object>> params, Function<C, Instant> asOf,
            Function<C, Instant> knownAt, String targetKey) {
            this(templateId, params, asOf, knownAt, targetKey, null);
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String templateId,
        Function<C, Map<String, Object>> params, String targetKey) {
        return at(templateId, params, ctx -> null, ctx -> null, targetKey);
    }

    /**
     * Runs the template only when {@code condition} holds on the context when the step is reached; otherwise the step
     * sends no statement and puts an empty list under {@code targetKey}, so what reads the rows needs no second
     * path. For a read that only some inputs need on a path whose statement count is budgeted (a hot path whose
     * ordinary case must not read more).
     */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition, String templateId,
        Function<C, Map<String, Object>> params, String targetKey) {
        return StepSpec.of(RunTemplate.class, new Metadata<>(templateId, params, ctx -> null, ctx -> null, targetKey,
            Objects.requireNonNull(condition, "condition must not be null")));
    }

    /**
     * Runs the template at a point in time computed from the context (docs/design/19-reports.md section 2.1); either
     * function may return null. A template declaring {@code timeSlice} takes its point in time from its parameters.
     */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> at(String templateId,
        Function<C, Map<String, Object>> params, Function<C, Instant> asOf, Function<C, Instant> knownAt,
        String targetKey) {
        return StepSpec.of(RunTemplate.class, new Metadata<>(templateId, params, asOf, knownAt, targetKey));
    }

    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor executor;
    private final int maxRows;

    public RunTemplate(SqlTemplateRegistry templates, AdvancedQueryExecutor executor,
        @Value("${jabiz.process.max-read-rows:100000}") int maxRows) {
        ProcessReads.checkMaximum(maxRows);
        this.templates = templates;
        this.executor = executor;
        this.maxRows = maxRows;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (!metadata.condition().test(ctx)) {
                ctx.put(metadata.targetKey(), List.of());
                return Mono.<Void>empty();
            }
            AdvancedQueryDefinition template = templates.find(metadata.templateId()).orElseThrow();
            Map<String, Object> params = metadata.params().apply(ctx);
            AdvancedQueryExecutor.At at = new AdvancedQueryExecutor.At(metadata.asOf().apply(ctx),
                metadata.knownAt().apply(ctx));
            // One row more than accepted tells a complete result from a cut one.
            return executor.all(template, params == null ? Map.of() : params, at, null, List.of(), maxRows + 1)
                .flatMap(page -> {
                    if (page.items().size() > maxRows) {
                        return Mono.error(ProcessReads.tooLarge("template " + metadata.templateId(), maxRows));
                    }
                    ctx.put(metadata.targetKey(), page.items().stream().map(RunTemplate::values).toList());
                    return Mono.<Void>empty();
                });
        });
    }

    private static Map<String, Object> values(SemanticRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        row.getAllColumns().forEach((name, value) -> values.put(name, value.value()));
        return Collections.unmodifiableMap(values);
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return templates.find(metadata.templateId()).isPresent()
            ? List.of()
            : List.of("unknown SQL template " + metadata.templateId());
    }
}
