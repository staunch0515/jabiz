package com.jabiz.firstlaunch;

import java.util.Objects;
import java.util.function.Function;

/**
 * One step of the content phase (docs/design/23-first-launch.md section 4): one process run as a sub-process of the
 * platform's step process {@code FIRST_LAUNCH_STEP}, in a transaction of its own.
 *
 * @param key     unique in the plan: shown in the log and the run's record, and the key later steps read the output
 *                by ({@link FirstLaunchResults#output})
 * @param process the name of the process to call (its latest version)
 * @param input   builds the process input from the results so far; must be an instance of the process's input type
 * @param asAccount the configuration key of the account whose user the step runs as ("acting", section 6), or
 *                {@code null} to run as the system
 * @param await   whether the step waits: it is run again (each time in a new transaction) until the process returns
 *                {@code Boolean.TRUE} or {@code jabiz.first-launch.content-timeout} has passed since the run began;
 *                for what comes from event delivery, such as the result of an approval
 * @param describe a sentence for the log, in the configuration's language when it can be ({@code null}: the key)
 */
public record FirstLaunchStep(String key, String process, Function<FirstLaunchResults, ?> input, String asAccount,
    boolean await, String describe) {

    /** The key of the platform's step that creates the configuration's account {@code accountKey}. */
    public static String accountStepKey(String accountKey) {
        return "account:" + accountKey;
    }

    public FirstLaunchStep {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(process, "process must not be null");
        Objects.requireNonNull(input, "input must not be null");
        describe = describe == null ? key : describe;
    }

    /** A step run as the system. */
    public static FirstLaunchStep run(String key, String process, Function<FirstLaunchResults, ?> input) {
        return new FirstLaunchStep(key, process, input, null, false, null);
    }

    /** A step run again until its process returns {@code true}. */
    public static FirstLaunchStep await(String key, String process, Function<FirstLaunchResults, ?> input) {
        return new FirstLaunchStep(key, process, input, null, true, null);
    }

    /** This step, run as the user of the configuration's account {@code accountKey}. */
    public FirstLaunchStep asAccount(String accountKey) {
        return new FirstLaunchStep(key, process, input, Objects.requireNonNull(accountKey), await, describe);
    }

    /** This step, with a sentence for the log. */
    public FirstLaunchStep describedAs(String sentence) {
        return new FirstLaunchStep(key, process, input, asAccount, await, sentence);
    }
}
