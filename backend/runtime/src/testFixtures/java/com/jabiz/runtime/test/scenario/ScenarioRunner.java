package com.jabiz.runtime.test.scenario;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalSpec;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.param.ParamService;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.job.JobRegistry;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.test.MutableClock;
import com.jabiz.runtime.web.ProblemStatuses;
import org.springframework.context.ApplicationContext;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replays a {@link Scenario} against a running application (docs/design/07-quality.md section 3). Processes run
 * through the {@link ProcessExecutor} with the scenario's actor as an explicit request context, as jobs do (decision
 * D11): entry-point permission checks do not apply, checks inside processes do. Inputs are converted exactly as the
 * process API converts request bodies, and failures are judged by the status and violations the API would answer
 * with ({@link ProblemStatuses}). All business time comes from the {@link MutableClock}.
 *
 * <p>A failed step throws an {@link AssertionError} naming the scenario, the step and what was expected.
 */
public final class ScenarioRunner {

    /** What a replay produced. */
    public record Result(Scenario scenario, Map<String, Object> variables, Map<Integer, Object> outputs,
        Map<String, List<Map<String, Object>>> snapshot, Instant snapshotAsOf) {}

    private final ProcessRegistry processes;
    private final ProcessExecutor executor;
    private final ProcessInputs inputs;
    private final SensitiveDataMasker masker;
    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor queries;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final DatasetEntityManager entityManager;
    private final ParamService params;
    private final MutableClock clock;
    private final JobRegistry jobs;
    private final JobRunner jobRunner;
    private final OutboxDeliverer deliverer;

    public ScenarioRunner(ApplicationContext context) {
        this.processes = context.getBean(ProcessRegistry.class);
        this.executor = context.getBean(ProcessExecutor.class);
        this.inputs = context.getBean(ProcessInputs.class);
        this.masker = context.getBean(SensitiveDataMasker.class);
        this.templates = context.getBean(SqlTemplateRegistry.class);
        this.queries = context.getBean(AdvancedQueryExecutor.class);
        this.datasets = context.getBean(DatasetRegistry.class);
        this.entities = context.getBean(EntityDefinitionRegistry.class);
        this.entityManager = context.getBean(DatasetEntityManager.class);
        this.params = context.getBean(ParamService.class);
        this.clock = context.getBean(MutableClock.class);
        this.jobs = context.getBean(JobRegistry.class);
        this.jobRunner = context.getBean(JobRunner.class);
        this.deliverer = context.getBean(OutboxDeliverer.class);
    }

    public Result run(Scenario scenario) {
        clock.set(scenario.clock());
        Map<String, Object> variables = new LinkedHashMap<>();
        Map<Integer, Object> outputs = new LinkedHashMap<>();
        for (Scenario.Step step : scenario.steps()) {
            try {
                runStep(scenario, step, variables, outputs);
            } catch (AssertionError e) {
                throw e;
            } catch (RuntimeException e) {
                throw failure(scenario, step, "could not run: " + e.getMessage(), e);
            }
        }
        if (scenario.snapshot() == null) {
            return new Result(scenario, variables, outputs, Map.of(), null);
        }
        Instant asOf = scenario.snapshot().asOf() == null ? clock.instant() : scenario.snapshot().asOf();
        return new Result(scenario, variables, outputs, export(scenario, scenario.snapshot().entities(), asOf), asOf);
    }

    private void runStep(Scenario scenario, Scenario.Step step, Map<String, Object> variables,
        Map<Integer, Object> outputs) {
        switch (step) {
            case Scenario.ProcessStep p -> {
                Object output;
                try {
                    output = runProcess(scenario, step, p.process(), ScenarioValues.resolveMap(p.input(), variables),
                        p.actor());
                } catch (AssertionError e) {
                    throw e;
                } catch (Throwable e) {
                    throw failure(scenario, step, "process " + p.process() + " failed with "
                        + ProblemStatuses.status(e) + " " + describe(ProblemStatuses.violations(e))
                        + ": " + e.getMessage(), e);
                }
                outputs.put(step.number(), output);
                p.save().forEach((name, path) -> variables.put(name, ScenarioValues.extract(output, path)));
                if (p.expectOutput() != null) {
                    Object expected = ScenarioValues.resolve(p.expectOutput(), variables);
                    if (!ScenarioValues.matches(expected, output)) {
                        throw failure(scenario, step, "output " + output + " does not match " + expected, null);
                    }
                }
            }
            case Scenario.AdvanceClock a -> clock.set(advance(clock.instant(), a.amount()));
            case Scenario.SetClock s -> clock.set(s.time());
            case Scenario.RunJob j -> {
                JobDefinition<?> job = jobs.find(j.job())
                    .orElseThrow(() -> failure(scenario, step, "unknown job " + j.job(), null));
                JobRunner.Outcome outcome = jobRunner.run(job, j.at() == null ? clock.instant() : j.at());
                if (!outcome.name().equals(j.outcome())) {
                    throw failure(scenario, step, "job " + j.job() + " ended " + outcome + ", not " + j.outcome(),
                        null);
                }
            }
            case Scenario.DeliverEvents d -> {
                // Until nothing is due; failed deliveries wait for their backoff, so this ends.
                for (int round = 0; round < 100 && deliverer.deliverPending().block() > 0; round++) {
                    // deliver again: consumers may publish events of their own
                }
            }
            case Scenario.Expect e -> expect(scenario, step, e.expectation(), variables);
            case Scenario.ExpectError e -> expectError(scenario, e, variables);
        }
    }

    private void expectError(Scenario scenario, Scenario.ExpectError step, Map<String, Object> variables) {
        Object output;
        try {
            output = runProcess(scenario, step, step.process(), ScenarioValues.resolveMap(step.input(), variables),
                step.actor());
        } catch (AssertionError e) {
            // The scenario itself is wrong (an unknown process, say): not a failure of the process.
            throw e;
        } catch (Throwable e) {
            int status = ProblemStatuses.status(e);
            List<Violation> violations = ProblemStatuses.violations(e);
            boolean ruleFound = step.ruleCode() == null || violations.stream().anyMatch(v ->
                v.ruleCode().equals(step.ruleCode()) && (step.field() == null || step.field().equals(v.field())));
            if (status != step.status() || !ruleFound) {
                throw failure(scenario, step, "expected " + step.status() + " " + step.ruleCode()
                    + (step.field() == null ? "" : " on " + step.field()) + " but got " + status + " "
                    + describe(violations) + ": " + e.getMessage(), e);
            }
            return;
        }
        throw failure(scenario, step, "expected " + step.status() + " " + step.ruleCode()
            + " but the process succeeded with " + output, null);
    }

    private void expect(Scenario scenario, Scenario.Step step, Scenario.Expectation expectation,
        Map<String, Object> variables) {
        switch (expectation) {
            case Scenario.QueryExpectation q -> {
                AdvancedQueryDefinition template = templates.find(q.query())
                    .orElseThrow(() -> failure(scenario, step, "unknown SQL template " + q.query(), null));
                List<Map<String, Object>> rows = as(scenario, queries.execute(template,
                    ScenarioValues.resolveMap(q.params(), variables)).map(ScenarioRunner::row).collectList());
                if (q.rows() != null && rows.size() != q.rows()) {
                    throw failure(scenario, step, q.query() + " returned " + rows.size() + " rows, expected "
                        + q.rows() + ": " + rows, null);
                }
                List<?> expected = (List<?>) ScenarioValues.resolve(q.values(), variables);
                for (int i = 0; i < expected.size(); i++) {
                    if (i >= rows.size() || !ScenarioValues.matches(expected.get(i), rows.get(i))) {
                        throw failure(scenario, step, q.query() + " row " + i + " is "
                            + (i < rows.size() ? rows.get(i) : "missing") + ", expected " + expected.get(i), null);
                    }
                }
            }
            case Scenario.EntityExpectation e -> {
                EntityDefinition def = entities.getOrThrow(e.entity());
                DatasetDefinition dataset = datasets.findForEntity(e.entity()).orElseThrow();
                Object id = ScenarioValues.resolve(e.id(), variables);
                EntityInstance found = as(scenario, entityManager.findById(dataset, def, id,
                    def.temporal ? e.asOf() : null, null));
                if (found == null) {
                    throw failure(scenario, step, e.entity() + " " + id + " not found", null);
                }
                Object expected = ScenarioValues.resolve(e.fields(), variables);
                Map<String, Object> actual = new LinkedHashMap<>(masker.hide(found).attributes());
                actual.put("version", found.version());
                if (!ScenarioValues.matches(expected, actual)) {
                    throw failure(scenario, step, e.entity() + " " + id + " is " + actual + ", expected " + expected,
                        null);
                }
            }
            case Scenario.ParamExpectation p -> {
                Instant asOf = p.asOf() == null ? clock.instant() : p.asOf();
                Object value = as(scenario, params.get(p.param(), asOf));
                Object expected = ScenarioValues.resolve(p.value(), variables);
                if (!ScenarioValues.matches(expected, value)) {
                    throw failure(scenario, step, "parameter " + p.param() + " at " + asOf + " is " + value
                        + ", expected " + expected, null);
                }
            }
        }
    }

    private Object runProcess(Scenario scenario, Scenario.Step step, String process, Map<String, Object> input,
        Scenario.Actor actor) {
        ProcessDefinition<?, ?, ?> definition = find(process)
            .orElseThrow(() -> failure(scenario, step, "unknown process " + process, null));
        ProcessResult<?> result = as(actor == null ? scenario.actor() : actor, Mono.defer(() -> run(definition, input)));
        return masker.toJsonWithoutSecrets(result.output());
    }

    private <I, O, C extends ProcessContext> Mono<ProcessResult<O>> run(ProcessDefinition<I, O, C> definition,
        Map<String, Object> input) {
        return executor.run(definition, inputs.convert(definition.inputType(), input), ExecutionOptions.NONE);
    }

    private java.util.Optional<ProcessDefinition<?, ?, ?>> find(String process) {
        int at = process.lastIndexOf('@');
        String name = at < 0 ? process : process.substring(0, at);
        String version = at < 0 ? "latest" : process.substring(at + 1);
        return "latest".equals(version) ? processes.findLatest(name) : processes.find(name, Integer.parseInt(version));
    }

    /**
     * Current rows of the entities through their default datasets (at {@code asOf} for temporal entities), without
     * sensitive fields, in primary key order, normalized ({@link SnapshotNormalizer}).
     */
    private Map<String, List<Map<String, Object>>> export(Scenario scenario, List<String> names, Instant asOf) {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String name : names) {
            EntityDefinition def = entities.getOrThrow(name);
            DatasetDefinition dataset = datasets.findForEntity(name).orElseThrow();
            int page = dataset.policy().maxQueryBatchSize();
            List<EntityInstance> all = new ArrayList<>();
            for (int offset = 0; ; offset += page) {
                EntityQuery query = EntityQuery.builder().offset(offset).limit(page).build();
                List<EntityInstance> found = as(scenario, Flux.defer(() -> entityManager.query(dataset, def, query,
                    def.temporal ? asOf : null, null)).collectList());
                all.addAll(found);
                if (found.size() < page) {
                    break;
                }
            }
            all.sort(Comparator.comparing(instance -> String.valueOf(instance.id())));
            snapshot.put(name, all.stream().map(instance -> row(def, masker.hide(instance))).toList());
        }
        return SnapshotNormalizer.normalize(snapshot);
    }

    private static Map<String, Object> row(EntityDefinition def, EntityInstance instance) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(def.primaryKey, instance.id());
        for (FieldDefinition field : def.fields.values()) {
            String name = field.name();
            if (name.equals(def.primaryKey) || field.sensitive() || TemporalSpec.DELETED.equals(name)) {
                continue;
            }
            // The version lives on the instance, not among its attributes.
            row.put(name, field.kind() instanceof SemanticKind.Version ? instance.version() : instance.get(name));
        }
        return row;
    }

    private static Map<String, Object> row(SemanticRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        row.getAllColumns().forEach((column, value) -> values.put(column, value.value()));
        return values;
    }

    private <T> T as(Scenario scenario, Mono<T> pipeline) {
        return as(scenario.actor(), pipeline);
    }

    private <T> T as(Scenario.Actor actor, Mono<T> pipeline) {
        RequestContext request = new RequestContext(actor.id(), actor.tenant(), Locale.ENGLISH, "scenario",
            actor.roles(), actor.permissions());
        try {
            return pipeline.contextWrite(view -> RequestContexts.put(view, request)).block();
        } catch (RuntimeException e) {
            Throwable cause = Exceptions.unwrap(e);
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    static Instant advance(Instant now, String amount) {
        try {
            return now.plus(Duration.parse(amount));
        } catch (DateTimeParseException e) {
            return now.atZone(ZoneOffset.UTC).plus(Period.parse(amount)).toInstant();
        }
    }

    private static String describe(List<Violation> violations) {
        return violations.stream().map(v -> v.ruleCode() + (v.field() == null ? "" : "@" + v.field())).toList()
            .toString();
    }

    private static AssertionError failure(Scenario scenario, Scenario.Step step, String message, Throwable cause) {
        AssertionError error = new AssertionError(scenario.source() + " (" + scenario.name() + "), step "
            + step.number() + ": " + message);
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }
}
