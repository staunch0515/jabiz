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
 * job runs is the registered process of that name and version.
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
        }
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
