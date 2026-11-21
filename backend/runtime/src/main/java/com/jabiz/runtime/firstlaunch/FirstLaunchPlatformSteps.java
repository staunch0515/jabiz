package com.jabiz.runtime.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchResults;
import com.jabiz.firstlaunch.FirstLaunchStep;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * A part of the platform that takes part in the first launch (docs/design/23-first-launch.md section 4), such as
 * creating the configuration's accounts: validated with the application's handler, and its steps run before the
 * handler's, in {@link #getOrder()} order. Not for applications: they implement
 * {@link com.jabiz.firstlaunch.FirstLaunchHandler}.
 */
public interface FirstLaunchPlatformSteps extends Ordered {

    /** Every problem of the platform's part of the configuration. */
    List<FirstLaunchProblem> validate(FirstLaunchConfig config);

    /** The steps, run before the application's. */
    List<FirstLaunchStep> plan(FirstLaunchConfig config);

    /**
     * What this part records with the run's {@code CONTENT_DONE} event for the waiting phase, as a JSON object (user
     * ids and the configuration's keys only, never an address or a name), or {@code null} for nothing. The details
     * of every part are recorded together, each under its {@link #detailKey()}.
     */
    default Object contentDoneDetail(FirstLaunchConfig config, FirstLaunchResults results) {
        return null;
    }

    /** The key this part's details are recorded under. */
    default String detailKey() {
        return getClass().getSimpleName();
    }
}
