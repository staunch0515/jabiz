package com.jabiz.firstlaunch;

import java.util.Optional;

/**
 * What the steps run so far gave (docs/design/23-first-launch.md section 4): later steps build their input from it.
 * Kept in memory for the content phase only; nothing is resumed (a failed content phase means an emptied database).
 */
public interface FirstLaunchResults {

    /** The output of the step with this key, if it has run and gave one. */
    Optional<Object> output(String stepKey);

    /** The output of the step with this key, as {@code type}. */
    default <T> T output(String stepKey, Class<T> type) {
        Object output = output(stepKey).orElseThrow(() -> new IllegalStateException(
            "Step " + stepKey + " has not run or gave no output"));
        return type.cast(output);
    }

    /**
     * The user id of the configuration's account {@code accountKey}, once the platform's step that creates it
     * ({@link FirstLaunchStep#accountStepKey}) has run.
     */
    String userId(String accountKey);
}
