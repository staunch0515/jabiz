package com.jabiz.runtime.check;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every {@link PlatformCheck} once all singletons exist and before the server accepts requests, and refuses to
 * start when any reports an error, listing all of them (docs/design/07-quality.md section 1). Warnings are logged.
 *
 * <p>{@code jabiz.platform-check.on-startup=false} skips the run; {@code platformCheck} does so and calls
 * {@link #runAll} itself to print the problems instead of failing.
 */
@Component
public class PlatformCheckRunner implements SmartInitializingSingleton {

    public static final String ON_STARTUP_PROPERTY = "jabiz.platform-check.on-startup";

    private static final Logger log = LoggerFactory.getLogger(PlatformCheckRunner.class);

    private final ObjectProvider<PlatformCheck> checks;
    private final boolean onStartup;

    public PlatformCheckRunner(ObjectProvider<PlatformCheck> checks, Environment environment) {
        this.checks = checks;
        this.onStartup = environment.getProperty(ON_STARTUP_PROPERTY, Boolean.class, true);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (onStartup) {
            List<CheckProblem> problems = runAll();
            problems.stream().filter(p -> !p.isError()).forEach(p -> log.warn("{}", p.format()));
            requireNoErrors(problems);
        }
    }

    /** Problems of every check, in check order; a check that throws is reported as a problem of its own. */
    public List<CheckProblem> runAll() {
        List<CheckProblem> problems = new ArrayList<>();
        checks.orderedStream().forEach(check -> problems.addAll(run(check)));
        return problems;
    }

    /** Runs the given checks and throws {@link PlatformCheckFailedException} if any reports an error. */
    public static void verify(PlatformCheck... checks) {
        List<CheckProblem> problems = new ArrayList<>();
        for (PlatformCheck check : checks) {
            problems.addAll(run(check));
        }
        requireNoErrors(problems);
    }

    private static List<CheckProblem> run(PlatformCheck check) {
        try {
            return check.check();
        } catch (RuntimeException e) {
            return List.of(CheckProblem.error("CHECK", check.getClass().getSimpleName(),
                "check could not run: " + e.getMessage()));
        }
    }

    private static void requireNoErrors(List<CheckProblem> problems) {
        List<CheckProblem> errors = problems.stream().filter(CheckProblem::isError).toList();
        if (!errors.isEmpty()) {
            throw new PlatformCheckFailedException(errors);
        }
    }
}
