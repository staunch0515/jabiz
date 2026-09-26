package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runs a registered SQL template and puts its rows, a {@code List<Map<String, Object>>} of result column to value,
 * into the context (docs/design/06-process.md section 2.1). Every entity of the template is read through its
 * dataset with the caller's scope (decision D10). The template's own permissions are not checked: running the
 * process required the process's permissions (decision D11).
 */
@Component
public class RunTemplate<C extends ProcessContext> implements StepHandler<RunTemplate.Metadata<C>, C>,
    CheckedStep<RunTemplate.Metadata<C>> {

    public record Metadata<C>(String templateId, Function<C, Map<String, Object>> params, String targetKey) {
        public Metadata {
            Objects.requireNonNull(templateId, "templateId must not be null");
            Objects.requireNonNull(params, "params must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String templateId,
        Function<C, Map<String, Object>> params, String targetKey) {
        return StepSpec.of(RunTemplate.class, new Metadata<>(templateId, params, targetKey));
    }

    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor executor;

    public RunTemplate(SqlTemplateRegistry templates, AdvancedQueryExecutor executor) {
        this.templates = templates;
        this.executor = executor;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            AdvancedQueryDefinition template = templates.find(metadata.templateId()).orElseThrow();
            Map<String, Object> params = metadata.params().apply(ctx);
            return executor.execute(template, params == null ? Map.of() : params)
                .map(RunTemplate::values)
                .collectList()
                .doOnNext(rows -> ctx.put(metadata.targetKey(), List.copyOf(rows)))
                .then();
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
