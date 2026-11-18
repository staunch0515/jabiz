package com.jabiz.runtime.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.process.ProcessRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Startup check {@code FIRST_LAUNCH} (docs/design/23-first-launch.md section 6): every process the application's
 * handler lets a step run as an account exists and can be called by a step (open or sub-process only, never an
 * internal or entry-only one); every control it lets the first launch publish has a key; at most one handler.
 */
@Component
public class FirstLaunchChecks implements PlatformCheck {

    static final String CATEGORY = "FIRST_LAUNCH";

    private final ObjectProvider<FirstLaunchHandler> handlers;
    private final ProcessRegistry registry;
    private final java.util.function.Predicate<String> policyExists;

    @org.springframework.beans.factory.annotation.Autowired
    public FirstLaunchChecks(ObjectProvider<FirstLaunchHandler> handlers, ProcessRegistry registry,
        com.jabiz.runtime.file.FilePolicyRegistry policies) {
        this(handlers, registry, name -> policies.find(name).isPresent());
    }

    FirstLaunchChecks(ObjectProvider<FirstLaunchHandler> handlers, ProcessRegistry registry,
        java.util.function.Predicate<String> policyExists) {
        this.handlers = handlers;
        this.registry = registry;
        this.policyExists = policyExists;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        List<FirstLaunchHandler> found = handlers.orderedStream().toList();
        if (found.size() > 1) {
            problems.add(CheckProblem.error(CATEGORY, "FirstLaunchHandler", found.size()
                + " first-launch handlers; an application has one"));
        }
        for (FirstLaunchHandler handler : found) {
            String where = "FirstLaunchHandler " + handler.name();
            for (String name : handler.actingProcesses()) {
                Optional<ProcessDefinition<?, ?, ?>> process = registry.findLatest(name);
                if (name.startsWith("FIRST_LAUNCH_")) {
                    problems.add(CheckProblem.error(CATEGORY, where, "acting process " + name + " is the first"
                        + " launch's own: a step runs it as the system or the platform does"));
                } else if (process.isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, where, "acting process " + name + " does not exist"));
                } else if (process.get().access() != ProcessAccess.OPEN
                    && process.get().access() != ProcessAccess.SUB_PROCESS_ONLY) {
                    problems.add(CheckProblem.error(CATEGORY, where, "acting process " + name + " is "
                        + process.get().access() + ": a step runs open or sub-process-only processes as an account"));
                }
            }
            for (String policy : handler.filePolicies()) {
                if (policy == null || policy.isBlank()) {
                    problems.add(CheckProblem.error(CATEGORY, where, "a file policy has no name"));
                } else if (!policyExists.test(policy)) {
                    problems.add(CheckProblem.error(CATEGORY, where, "file policy " + policy + " does not exist"));
                }
            }
            for (String key : handler.publishableControlKeys()) {
                if (key == null || key.isBlank()) {
                    problems.add(CheckProblem.error(CATEGORY, where, "a publishable control has no key"));
                }
            }
        }
        return problems;
    }
}
