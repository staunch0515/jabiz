package com.jabiz.runtime.firstlaunch.accounts;

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
 * Startup check {@code FIRST_LAUNCH} (docs/design/23-first-launch.md section 9): every process a ready account's steps
 * may run ({@link FirstLaunchHandler#accountReadyProcesses()}) exists, is sub-process-only or internal (never open or
 * entry-only), is not the platform's own ({@code FIRST_LAUNCH_}), and is not an acting process too - acting in the
 * content phase and the system's steps for a ready account are kept apart.
 */
@Component
public class FirstLaunchReadyChecks implements PlatformCheck {

    static final String CATEGORY = "FIRST_LAUNCH";

    private final ObjectProvider<FirstLaunchHandler> handlers;
    private final ProcessRegistry registry;

    public FirstLaunchReadyChecks(ObjectProvider<FirstLaunchHandler> handlers, ProcessRegistry registry) {
        this.handlers = handlers;
        this.registry = registry;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        for (FirstLaunchHandler handler : handlers.orderedStream().toList()) {
            String where = "FirstLaunchHandler " + handler.name();
            for (String name : handler.accountReadyProcesses()) {
                Optional<ProcessDefinition<?, ?, ?>> process = registry.findLatest(name);
                if (name.startsWith("FIRST_LAUNCH_")) {
                    problems.add(CheckProblem.error(CATEGORY, where, "account-ready process " + name
                        + " is the platform's own (FIRST_LAUNCH_)"));
                } else if (process.isEmpty()) {
                    problems.add(CheckProblem.error(CATEGORY, where, "account-ready process " + name
                        + " does not exist"));
                } else if (process.get().access() != ProcessAccess.SUB_PROCESS_ONLY
                    && process.get().access() != ProcessAccess.INTERNAL) {
                    problems.add(CheckProblem.error(CATEGORY, where, "account-ready process " + name + " is "
                        + process.get().access() + ": only sub-process-only or internal processes"));
                } else if (handler.actingProcesses().contains(name)) {
                    problems.add(CheckProblem.error(CATEGORY, where, "account-ready process " + name
                        + " is an acting process too: the two lists are apart"));
                }
            }
        }
        return problems;
    }
}
