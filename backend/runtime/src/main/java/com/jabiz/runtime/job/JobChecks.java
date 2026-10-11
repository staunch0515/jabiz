package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.process.ProcessRegistry;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup check of scheduled jobs (category {@code JOB}): names are unique, cron expressions parse, and the process a
 * job runs is the registered process of that name and version. The process is {@code internal()} (decision D41): an
 * {@code entryOnly()} or {@code subProcessOnly()} one cannot run as the system's root process and is an error; an
 * open one is a warning unless the job declares {@code manualToo()}.
 */
@Component
public class JobChecks implements PlatformCheck {

    static final String CATEGORY = "JOB";

    private final JobRegistry jobs;
    private final ProcessRegistry processes;

    public JobChecks(JobRegistry jobs, ProcessRegistry processes) {
        this.jobs = jobs;
        this.processes = processes;
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
            problems.addAll(accessProblems(CATEGORY, where, job.process(), job.alsoRunByHand()));
        }
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
