package com.jabiz.runtime.job;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.template.SqlText;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Startup check of scheduled jobs (category {@code JOB}): names are unique, cron expressions parse, and the process a
 * job runs is the registered process of that name and version. A single-process job's process is {@code internal()}
 * (decision D41): an {@code entryOnly()} or {@code subProcessOnly()} one cannot run as the system's root process and is
 * an error; an open one is a warning unless the job declares {@code manualToo()}. Per-item jobs (decision D42) also
 * need: a selection template that exists, is not public, declares {@code list.defaultSort} and {@code list.key}, gets
 * every required parameter and no unknown one, and has the key column; an item process declared {@code internal()},
 * any other access being an error (its input comes from the selection, not from a person; {@code manualToo()} does
 * not apply); an {@code itemLockWait} within {@code jabiz.process.lock-timeout}; and, as a warning, a
 * {@code parallelism} of at most half the connection pool. The selection reads masked fields masked: an item key from
 * one is an error, any other masked field the template names a warning.
 */
@Component
public class JobChecks implements PlatformCheck {

    static final String CATEGORY = "JOB";

    private final JobRegistry jobs;
    private final ProcessRegistry processes;
    private final Function<String, Optional<AdvancedQueryDefinition>> templates;
    private final Function<String, Optional<EntityDefinition>> entities;
    private final Duration lockTimeout;
    private final int poolSize;

    @Autowired
    public JobChecks(JobRegistry jobs, ProcessRegistry processes, SqlTemplateRegistry templates,
        EntityDefinitionRegistry entities, @Value("${jabiz.process.lock-timeout:30s}") Duration lockTimeout,
        @Value("${spring.r2dbc.pool.max-size:10}") int poolSize) {
        this(jobs, processes, templates::find, entities::find, lockTimeout, poolSize);
    }

    JobChecks(JobRegistry jobs, ProcessRegistry processes,
        Function<String, Optional<AdvancedQueryDefinition>> templates,
        Function<String, Optional<EntityDefinition>> entities, Duration lockTimeout, int poolSize) {
        this.jobs = jobs;
        this.processes = processes;
        this.templates = templates;
        this.entities = entities;
        this.lockTimeout = lockTimeout;
        this.poolSize = poolSize;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JobDefinition<?> job : jobs.all()) {
            String where = "job " + job.name();
            if (!names.add(job.name())) {
                problems.add(CheckProblem.error(CATEGORY, where, "is declared more than once"));
            }
            if (!CronExpression.isValidExpression(job.cron())) {
                problems.add(CheckProblem.error(CATEGORY, where, "cron expression '" + job.cron()
                    + "' is not valid (six fields: second minute hour day month weekday)"));
            }
            problems.addAll(processProblems(processes, CATEGORY, where, job.process()));
            if (job.kind() == JobDefinition.Kind.PER_ITEM) {
                problems.addAll(perItemProblems(job, where));
            } else {
                problems.addAll(accessProblems(CATEGORY, where, job.process(), job.alsoRunByHand()));
            }
        }
        return problems;
    }

    private List<CheckProblem> perItemProblems(JobDefinition<?> job, String where) {
        List<CheckProblem> problems = new ArrayList<>();
        // Stricter than a single-process job: an open item process is an error too, there is no manualToo().
        if (job.process().access() != ProcessAccess.INTERNAL) {
            problems.add(CheckProblem.error(CATEGORY, where, "runs item process " + job.process().name()
                + ", which is " + job.process().access() + ", not internal(): item processes take their input from "
                + "the selection (decision D41)"));
        }
        if (job.itemLockWait().compareTo(lockTimeout) > 0) {
            problems.add(CheckProblem.error(CATEGORY, where, "itemLockWait " + job.itemLockWait()
                + " is longer than jabiz.process.lock-timeout " + lockTimeout));
        }
        if (job.parallelism() > poolSize / 2) {
            problems.add(CheckProblem.warning(CATEGORY, where, "parallelism " + job.parallelism()
                + " is more than half the connection pool (" + poolSize + "): requests may wait for connections"));
        }
        JobItems items = job.items();
        Optional<AdvancedQueryDefinition> found = templates.apply(items.templateId());
        if (found.isEmpty()) {
            problems.add(CheckProblem.error(CATEGORY, where, "selects with SQL template " + items.templateId()
                + ", which does not exist"));
            return problems;
        }
        AdvancedQueryDefinition template = found.get();
        String selection = "selection template " + template.queryId();
        if (template.publicAccess()) {
            problems.add(CheckProblem.error(CATEGORY, where, selection + " is public; a job's selection is not"));
        }
        if (template.list().defaultSort() == null) {
            problems.add(CheckProblem.error(CATEGORY, where, selection + " declares no list.defaultSort"));
        }
        if (template.list().key().isEmpty()) {
            problems.add(CheckProblem.error(CATEGORY, where, selection + " declares no list.key"));
        }
        Optional<ProjectedField> key = template.result(items.keyColumn());
        if (key.isEmpty()) {
            problems.add(CheckProblem.error(CATEGORY, where, selection + " has no result column "
                + items.keyColumn() + " for the item key"));
        } else if (masked(key.get().sourceEntity(), key.get().sourceField())) {
            problems.add(CheckProblem.error(CATEGORY, where, selection + " takes the item key from masked field "
                + key.get().sourceEntity() + "." + key.get().sourceField() + "; the selection reads it masked"));
        }
        // The selection runs with every masked field masked: a column computed from one is "***" there.
        SqlText.placeholders(SqlText.mask(template.sqlTemplate())).stream()
            .filter(placeholder -> placeholder.field() != null && masked(placeholder.entity(), placeholder.field()))
            .map(placeholder -> placeholder.entity() + "." + placeholder.field())
            .distinct()
            .forEach(field -> problems.add(CheckProblem.warning(CATEGORY, where, selection + " names masked field "
                + field + ", which the selection reads masked")));
        problems.addAll(parameterProblems(job, where, selection, template));
        return problems;
    }

    private boolean masked(String entity, String field) {
        if (entity == null || field == null) {
            return false;
        }
        return entities.apply(entity).map(def -> def.fields.get(field)).map(FieldDefinition::isMasked)
            .orElse(false);
    }

    /** The parameters a run gives (from any scheduled time: the function is free of I/O) must fit the template. */
    private static List<CheckProblem> parameterProblems(JobDefinition<?> job, String where, String selection,
        AdvancedQueryDefinition template) {
        Map<String, Object> given;
        try {
            given = job.items().parameters().apply(Instant.EPOCH);
        } catch (RuntimeException e) {
            return List.of(CheckProblem.error(CATEGORY, where, "the parameters of " + selection
                + " could not be computed: " + e));
        }
        Set<String> keys = given == null ? Set.of() : given.keySet();
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        for (QueryParameter parameter : template.parameters()) {
            declared.add(parameter.name());
            if (parameter.required() && parameter.defaultValue() == null && !keys.contains(parameter.name())) {
                problems.add(CheckProblem.error(CATEGORY, where, selection + " needs parameter " + parameter.name()
                    + ", which the job does not give"));
            }
        }
        keys.stream().filter(key -> !declared.contains(key)).sorted().forEach(key -> problems.add(
            CheckProblem.error(CATEGORY, where, selection + " has no parameter " + key)));
        return problems;
    }

    /**
     * What a process run by the system on its own (a job, an event consumer) must be (decision D41): {@code internal()}
     * as a rule; open only when it is also run by hand ({@code manualToo}, else a warning); never {@code entryOnly()}
     * or {@code subProcessOnly()}, which the system cannot start.
     */
    public static List<CheckProblem> accessProblems(String category, String where, ProcessDefinition<?, ?, ?> process,
        boolean manualToo) {
        String target = "runs process " + process.name() + " v" + process.version();
        return switch (process.access()) {
            case INTERNAL -> List.of();
            case OPEN -> manualToo ? List.of() : List.of(CheckProblem.warning(category, where, target
                + ", which is open: declare it internal() (decision D41), or manualToo() if it is also run by hand"));
            case ENTRY_ONLY, SUB_PROCESS_ONLY -> List.of(CheckProblem.error(category, where, target + ", which is "
                + process.access() + " and cannot be started by the system: declare it internal() (decision D41)"));
        };
    }

    /** The process must be the one registered under its name and version, or it would bypass the process checks. */
    public static List<CheckProblem> processProblems(ProcessRegistry processes, String category, String where,
        ProcessDefinition<?, ?, ?> process) {
        return processes.find(process.name(), process.version())
            .filter(registered -> registered == process)
            .map(registered -> List.<CheckProblem>of())
            .orElseGet(() -> List.of(CheckProblem.error(category, where, "runs process " + process.name() + " v"
                + process.version() + ", which is not registered as a bean")));
    }
}
