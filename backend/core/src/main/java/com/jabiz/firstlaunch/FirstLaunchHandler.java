package com.jabiz.firstlaunch;

import java.util.List;
import java.util.Set;

/**
 * An application's part of the first launch (docs/design/23-first-launch.md): a bean that reads the application's
 * sections of the configuration, reports their problems and gives the steps of the content phase. Synchronous and
 * free of I/O beyond {@link FirstLaunchConfig#files()}: the platform runs the steps. At most one handler per
 * application; the platform's own steps (accounts) run before the handler's.
 */
public interface FirstLaunchHandler {

    /** The application's name, for the log. */
    String name();

    /**
     * Every problem of the application's sections, all at once (an empty list when there is none). Called at startup
     * on every instance that has a configuration, before anything is written; any problem stops the startup.
     */
    List<FirstLaunchProblem> validate(FirstLaunchConfig config);

    /**
     * The steps of the content phase, in order. Called only once {@link #validate} reported nothing; may assume a
     * valid configuration.
     */
    List<FirstLaunchStep> plan(FirstLaunchConfig config);

    /**
     * The processes a step may run as an account ({@link FirstLaunchStep#asAccount}); the platform refuses any other
     * (section 6). Empty by default: no acting.
     */
    default Set<String> actingProcesses() {
        return Set.of();
    }

    /**
     * The controlled parameters the configuration's {@code params} may set (section 7); the platform refuses any
     * other. Empty by default.
     */
    default Set<String> settableParams() {
        return Set.of();
    }

    /**
     * What is left for people once the first launch is done, one sentence each, in {@code config.language()}: listed
     * by the report (section 8). Empty by default.
     */
    default List<String> remaining(FirstLaunchConfig config) {
        return List.of();
    }
}
