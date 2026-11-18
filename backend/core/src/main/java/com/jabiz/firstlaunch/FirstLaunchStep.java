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
 * @param file     for a file step ({@link #file}): where the bytes come from and how they are stored; {@code null}
 *                 for a process step
 */
public record FirstLaunchStep(String key, String process, Function<FirstLaunchResults, ?> input, String asAccount,
    boolean await, String describe, FileSource file) {

    /**
     * The bytes of a file step (docs/design/23-first-launch.md section 6): read from the configuration's directory
     * only ({@link FirstLaunchConfig.Files}), then given to {@code extract} (to pick a file out of a package, say),
     * then stored through the platform's upload pipeline under {@code policy}, as the step's account.
     *
     * @param policy  the file policy (one the handler names in {@link FirstLaunchHandler#filePolicies()})
     * @param path    the file in the configuration's directory
     * @param extract the stored bytes from the file's bytes ({@code Function.identity()} for the file itself)
     * @param name    the stored file's name for downloads (a neutral one; it is not recorded in the operation)
     */
    public record FileSource(String policy, String path, Function<byte[], byte[]> extract, String name) {
        public FileSource {
            Objects.requireNonNull(policy, "policy must not be null");
            Objects.requireNonNull(path, "path must not be null");
            extract = extract == null ? Function.identity() : extract;
            name = name == null || name.isBlank() ? "file" : name;
        }
    }

    /** The process the platform shows for a file step in the dry run and the log. */
    public static final String FILE_PROCESS = "(file)";

    /**
     * The key by which {@link FirstLaunchResults#output} finds the output of the platform's step that creates the
     * configuration's account {@code accountKey}. The step itself is keyed by the account's place
     * ({@link #accountStepKeyAt}), since step keys are recorded with the operations and an account's key is its user
     * name; the results map one to the other.
     */
    public static String accountStepKey(String accountKey) {
        return "account:" + accountKey;
    }

    /** The key of the platform's step that creates the configuration's account at {@code position} (from 0). */
    public static String accountStepKeyAt(int position) {
        return "account:#" + (position + 1);
    }

    public FirstLaunchStep {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(process, "process must not be null");
        Objects.requireNonNull(input, "input must not be null");
        describe = describe == null ? key : describe;
    }

    public FirstLaunchStep(String key, String process, Function<FirstLaunchResults, ?> input, String asAccount,
        boolean await, String describe) {
        this(key, process, input, asAccount, await, describe, null);
    }

    /**
     * A file step: stores a file of the configuration's directory through the platform's upload pipeline; its output
     * is the stored file's id ({@link java.util.UUID}). Usually {@link #asAccount}: the file is then that account's
     * upload, as content processes require of the files they are given.
     */
    public static FirstLaunchStep file(String key, FileSource source) {
        return new FirstLaunchStep(key, FILE_PROCESS, results -> source, null, false, null,
            Objects.requireNonNull(source, "source must not be null"));
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
        return new FirstLaunchStep(key, process, input, Objects.requireNonNull(accountKey), await, describe, file);
    }

    /** This step, with a sentence for the log. */
    public FirstLaunchStep describedAs(String sentence) {
        return new FirstLaunchStep(key, process, input, asAccount, await, sentence, file);
    }
}
