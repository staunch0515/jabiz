package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The declared {@link JobDefinition} beans. Duplicate names are reported by {@link JobChecks}. */
@Component
public class JobRegistry {

    private final List<JobDefinition<?>> jobs;

    public JobRegistry(ObjectProvider<JobDefinition<?>> beans) {
        this.jobs = beans.orderedStream().toList();
    }

    public List<JobDefinition<?>> all() {
        return jobs;
    }

    public Optional<JobDefinition<?>> find(String name) {
        return jobs.stream().filter(job -> job.name().equals(name)).findFirst();
    }
}
