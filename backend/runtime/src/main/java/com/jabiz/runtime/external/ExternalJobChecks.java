package com.jabiz.runtime.external;

import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.file.FilePolicy;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.file.FilePolicyRegistry;
import com.jabiz.runtime.process.ProcessRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Startup self-check {@code EXTERNAL} for external job types (decision D38 item 3), every problem at once: each type
 * has a valid, unique name and sound limits ({@link ExternalJobType#problems()}), exactly one bean of its handler, only
 * declared services whose request timeout is shorter than the attempt's, file policies that exist and take the
 * output files' size (decision D50), and completion and failure processes that
 * exist, are {@code internal()} (anyone could fake a
 * result otherwise) and take {@link ExternalJobCompletion} and {@link ExternalJobFailure}. That a step starts a
 * declared type is the process check's ({@code StartExternalJob} is a checked step).
 */
@Component
public class ExternalJobChecks implements PlatformCheck {

    private final ExternalJobRegistry jobs;
    private final ExternalServiceRegistry services;
    private final ProcessRegistry processes;
    private final ApplicationContext beans;

    private final FilePolicyRegistry policies;

    @Autowired
    public ExternalJobChecks(ExternalJobRegistry jobs, ExternalServiceRegistry services, ProcessRegistry processes,
        ApplicationContext beans, FilePolicyRegistry policies) {
        this.jobs = jobs;
        this.services = services;
        this.processes = processes;
        this.beans = beans;
        this.policies = policies;
    }

    /** The checks with the file policies the application context declares (none when it has no registry of them). */
    public ExternalJobChecks(ExternalJobRegistry jobs, ExternalServiceRegistry services, ProcessRegistry processes,
        ApplicationContext beans) {
        this(jobs, services, processes, beans, beans.getBeanProvider(FilePolicyRegistry.class)
            .getIfAvailable(() -> new FilePolicyRegistry(beans.getBeanProvider(FilePolicy.class))));
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ExternalJobType<?, ?> type : jobs.declared()) {
            String at = "job " + type.name();
            if (type.name() != null && !names.add(type.name())) {
                problems.add(error(at, "declared twice"));
            }
            type.problems().forEach(problem -> problems.add(error(at, problem)));
            if (type.handler() != null) {
                int count = beans.getBeanNamesForType(type.handler()).length;
                if (count != 1) {
                    problems.add(error(at, "handler: expected exactly one bean of type " + type.handler().getName()
                        + ", found " + count));
                }
            }
            for (String service : type.services()) {
                if (service == null) {
                    continue;
                }
                var declared = services.find(service);
                if (declared.isEmpty()) {
                    problems.add(error(at, "services: no external service " + service + " is declared"));
                } else if (declared.get().requestTimeout().compareTo(type.timeout()) >= 0) {
                    // Otherwise one slow request outlasts the attempt, which times out with nothing to show for it.
                    problems.add(error(at, "timeout: " + type.timeout() + " must be longer than the request timeout "
                        + declared.get().requestTimeout() + " of service " + service));
                }
            }
            files(type, at, problems);
            callback(type.onCompleted(), ExternalJobCompletion.class, "onCompleted", at, problems);
            callback(type.onFailed(), ExternalJobFailure.class, "onFailed", at, problems);
        }
        return problems;
    }

    /**
     * The file policies of the type's files are declared (decision D50), and a type does not promise output files its
     * policy would refuse for their size.
     */
    private void files(ExternalJobType<?, ?> type, String at, List<CheckProblem> problems) {
        if (type.inputFiles() != null) {
            for (String policy : type.inputFiles().policies()) {
                if (policy != null && !policy.isBlank() && policies.find(policy).isEmpty()) {
                    problems.add(error(at, "inputFiles: no file policy " + policy + " is declared"));
                }
            }
        }
        if (type.outputFiles() != null && type.outputFiles().policy() != null
            && !type.outputFiles().policy().isBlank()) {
            Optional<FilePolicy> policy = policies.find(type.outputFiles().policy());
            if (policy.isEmpty()) {
                problems.add(error(at, "outputFiles: no file policy " + type.outputFiles().policy()
                    + " is declared"));
            } else if (type.outputFiles().maxBytes() > policy.get().maxBytes()) {
                problems.add(error(at, "outputFiles: files of up to " + type.outputFiles().maxBytes() + " bytes, "
                    + "but policy " + policy.get().name() + " takes at most " + policy.get().maxBytes()));
            }
        }
    }

    private void callback(String process, Class<?> input, String what, String at, List<CheckProblem> problems) {
        if (process == null || process.isBlank()) {
            return;
        }
        Optional<ProcessDefinition<?, ?, ?>> found = processes.findLatest(process);
        if (found.isEmpty()) {
            problems.add(error(at, what + ": no process " + process + " is registered"));
            return;
        }
        ProcessDefinition<?, ?, ?> definition = found.get();
        if (!definition.internal()) {
            problems.add(error(at, what + ": process " + process + " must be internal(); anyone could run it with a "
                + "made-up result otherwise"));
        }
        if (!definition.inputType().equals(input)) {
            problems.add(error(at, what + ": process " + process + " must take " + input.getSimpleName() + ", not "
                + definition.inputType().getName()));
        }
    }

    private static CheckProblem error(String location, String message) {
        return CheckProblem.error(ExternalChecks.CATEGORY, location, message);
    }
}
