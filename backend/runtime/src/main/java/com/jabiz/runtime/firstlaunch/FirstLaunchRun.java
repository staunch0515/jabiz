package com.jabiz.runtime.firstlaunch;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The first launch as recorded (docs/design/23-first-launch.md section 4): the one run there can be and its state.
 *
 * @param runId        the run
 * @param configHash   the hash of the configuration it began with
 * @param startedTime  when it began
 * @param state        where it is
 * @param stateTime    when it got there ({@code startedTime} while running)
 * @param failedStep   for {@link State#FAILED}: the step that failed (or {@code null})
 * @param failedReason for {@link State#FAILED}: why, as the log said it
 */
public record FirstLaunchRun(UUID runId, String configHash, Instant startedTime, State state, Instant stateTime,
    String failedStep, String failedReason) {

    /**
     * {@link #RUNNING}: the content phase is being done and the application does not serve; {@link #CONTENT_DONE}:
     * the content is in place, the application serves and the first launch waits for people; the others have
     * ended it for good.
     */
    public enum State { RUNNING, CONTENT_DONE, DONE, FAILED, EXPIRED;

        /** Whether the run is over. */
        public boolean ended() {
            return this == DONE || this == FAILED || this == EXPIRED;
        }
    }

    public FirstLaunchRun {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }
}
