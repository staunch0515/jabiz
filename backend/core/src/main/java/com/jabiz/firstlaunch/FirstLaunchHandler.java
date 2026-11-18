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
     * The controls whose initial values the first launch publishes without a second person (decision D82), by the
     * target key of their controlled change: a parameter's key, an approval rule's code. A step proposes the change
     * (as the system, in the run) and then calls {@code CONTROL_CHANGE_FIRST_LAUNCH_PUBLISH}; the platform refuses
     * any key not listed here. Empty by default: nothing is published.
     */
    default Set<String> publishableControlKeys() {
        return Set.of();
    }

    /**
     * The steps to run for the configuration's account {@code account} once it is ready in the waiting phase - once a
     * configured role of it is in effect, so its user accepted the invitation (the address is proven) and bound a second
     * factor (docs/design/23-first-launch.md section 9), such as adding it to what it is to be a member of. Empty by
     * default.
     *
     * <p>Only {@link FirstLaunchStep#run} steps, each running a process of {@link #actingProcesses()}, as the system;
     * keys unique within the account. {@code results} gives only {@link FirstLaunchResults#userId}: the content phase's
     * outputs are not kept. Each step runs once per account (recorded); a failure is retried by the job and, until it
     * succeeds, listed in the report.
     */
    default List<FirstLaunchStep> whenAccountReady(FirstLaunchConfig config, FirstLaunchAccount account,
        FirstLaunchResults results) {
        return List.of();
    }

    /**
     * What is left for people once the first launch is done, one sentence each, in {@code config.language()}: listed
     * by the report (section 8). Empty by default.
     */
    default List<String> remaining(FirstLaunchConfig config) {
        return List.of();
    }
}
