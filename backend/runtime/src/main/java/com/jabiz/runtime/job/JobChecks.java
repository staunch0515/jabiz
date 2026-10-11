package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
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
 * job runs is the registered process of that name and version. Per-item jobs (decision D42) also need: a selection
 * template that exists, is not public, declares {@code list.defaultSort} and {@code list.key}, gets every required
 * parameter and no unknown one, and has the key column; an item process declared {@code internal()} (D41: its input
 * comes from the selection, not from a person); an {@code itemLockWait} within {@code jabiz.process.lock-timeout}; and,
 * as a warning, a {@code parallelism} of at most half the connection pool.
 */
@Component
public class JobChecks implements PlatformCheck {

    static final String CATEGORY = "JOB";

    private final JobRegistry jobs;
    private final ProcessRegistry processes;
    private final Function<String, Optional<AdvancedQueryDefinition>> templates;
    private final Duration lockTimeout;
    private final int poolSize;

    @Autowired
    public JobChecks(JobRegistry jobs, ProcessRegistry processes, SqlTemplateRegistry templates,
        @Value("${jabiz.process.lock-timeout:30s}") Duration lockTimeout,
        @Value("${spring.r2dbc.pool.max-size:10}") int poolSize) {
        this(jobs, processes, templates::find, lockTimeout, poolSize);
    }

    JobChecks(JobRegistry jobs, ProcessRegistry processes,
        Function<String, Optional<AdvancedQueryDefinition>> templates, Duration lockTimeout, int poolSize) {
        this.jobs = jobs;
        this.processes = processes;
        this.templates = templates;
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
            }
        }
        return problems;
    }

    private List<CheckProblem> perItemProblems(JobDefinition<?> job, String where) {
        List<CheckProblem> problems = new ArrayList<>();
        if (!job.process().internal()) {
            problems.add(CheckProblem.error(CATEGORY, where, "runs item process " + job.process().name()
                + ", which is not internal(): item processes take their input from the selection (decision D41)"));
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
        if (template.result(items.keyColumn()).isEmpty()) {
            problems.add(CheckProblem.error(CATEGORY, where, selection + " has no result column "
                + items.keyColumn() + " for the item key"));
        }
        problems.addAll(parameterProblems(job, where, selection, template));
        return problems;
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
