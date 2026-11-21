package com.jabiz.firstlaunch;

import java.util.Objects;

/**
 * A problem of the first-launch configuration, in words an operator acts on (docs/design/23-first-launch.md section
 * 3): where it is, what is wrong and how to fix it. Every problem stops the startup.
 *
 * @param location where in the configuration, e.g. {@code accounts[2].email} or {@code sponsors/hq/package.json}
 * @param problem  what is wrong
 * @param fix      what to change
 */
public record FirstLaunchProblem(String location, String problem, String fix) {

    public FirstLaunchProblem {
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(problem, "problem must not be null");
        fix = fix == null ? "" : fix;
    }

    /** {@code location | problem | fix}, as the log shows it. */
    public String format() {
        return location + " | " + problem + (fix.isEmpty() ? "" : " | " + fix);
    }
}
